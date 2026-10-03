# OTA 固件灰度发布编排（本地可运行）

一套纯本地内存实现的固件灰度发布编排能力：设备与固件包登记、兼容性校验、
**按波次（wave）分波逐步放量**、按批次灰度放开、暂停 / 恢复 / 回滚、进度幂等上报、
失败率超阈自动熔断回滚。**不依赖数据库、消息队列或任何外部服务**，`mvn test` 即可完整验证。

## 1. 发布流程

```
登记固件包 ──┐
            ├──> 兼容性校验（逐台，区分拒绝原因）
登记设备  ──┘            │
                        ├─ 不兼容：按精确原因拒绝，整批不创建
                        └─ 全部兼容：按给定顺序拆成若干波次，创建批次（PENDING）
                                   │
                          start（只放开第 0 波，ACTIVE）
                                   │
                  仅当前波次设备可上报：心跳 / 下载 / 安装 / 成功 / 失败（全部幂等）
                                   │
             ┌─────────────────────┼───────────────────────────────┐
             ▼                     ▼                                 ▼
        pause()/resume()     当前波全部到终态                   失败率 ≥ 阈值
        整批一起生效          且未熔断 → advanceWave()           → 熔断 CIRCUIT_OPEN
                             原子放开下一波（不能跳波）            → 自动回滚 ROLLED_BACK
             ┌───────────────最后一波也走完 → COMPLETED
             ▼
  任意可回滚状态下都可以人工 rollback()（幂等，支持中断续跑）
```

### 批次状态（`model.BatchStatus`）

| 状态 | 含义 | 是否接受进度上报 |
| --- | --- | --- |
| `PENDING` | 已创建未放开 | 否（`BATCH_NOT_STARTED`） |
| `ACTIVE` | 推送中 | 是 |
| `PAUSED` | 人工暂停 | 否（`BATCH_PAUSED`）；心跳仍接受 |
| `CIRCUIT_OPEN` | 已熔断，回滚进行中 | 否（`BATCH_CIRCUIT_OPEN`） |
| `ROLLED_BACK` | 回滚完成 | 否（`BATCH_ROLLED_BACK`） |
| `COMPLETED` | 全部设备升级成功 | 重复成功上报按幂等处理，其余拒绝 |

### 设备级状态（`model.DeviceUpgradeState`）

`NOT_STARTED → IN_PROGRESS（下载/安装）→ SUCCEEDED | FAILED`，
回滚后再迁移到 `ROLLBACK_SUCCEEDED / ROLLBACK_SKIPPED / ROLLBACK_FAILED`。
终态（SUCCEEDED/FAILED）**不可互相翻转**。

## 2. 波次拆分与推进规则

建批时通过带 `waveDeviceIds` 的重载把设备**按给定顺序**拆成若干有先后的波次：

```java
// 3 个波次：先 d1/d2，再 d3/d4，最后 d5；start 后只放开第 0 波
orch.createBatch("b1", "fw-2.0",
        List.of("d1", "d2", "d3", "d4", "d5"), 50,
        List.of(List.of("d1", "d2"), List.of("d3", "d4"), List.of("d5")));
```

拆分约束（任一不满足，**整批不创建**，原因码可区分）：

| 原因码 | 触发条件 |
| --- | --- |
| `WAVES_EMPTY` | 一个波次都没给 |
| `WAVE_EMPTY` | 某个波次为空（每波至少 1 台） |
| `WAVE_DEVICE_DUPLICATED` | 同一台设备出现在多个波次 |
| `WAVE_DEVICE_NOT_IN_BATCH` | 波次里出现了设备清单之外的设备 |
| `WAVES_NOT_COVERING_ALL_DEVICES` | 波次没有恰好覆盖全部设备（有遗漏） |

> 不传 `waveDeviceIds` 的旧建批接口等价于“全部设备在唯一一个波次”，行为与上一版本完全一致。

### 波次状态（`model.WaveStatus`）与推进规则

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 尚未放开，设备上报一律拒绝（`WAVE_NOT_OPENED`）；回滚后仍没轮到的波次也保持此状态（原样没动） |
| `ACTIVE` | 当前唯一放开的波次，只有这一波的设备接受进度上报 |
| `FINISHED` | 波内全部设备到达终态（SUCCEEDED/FAILED） |
| `ROLLED_BACK` | 已放开过、随批次回滚完成处置（由 ACTIVE/FINISHED 收敛而来），区别于“压根没轮到”的 PENDING |

推进规则（`advanceWave()`，返回 `WaveAdvanceResult` 带原因码）：

1. `start()` 只把**第 0 波**置为 ACTIVE，其余波次原地 PENDING。
2. 当前波设备**全部拿到最终结果**（SUCCEEDED/FAILED）且这一波**没有触发熔断**，
   波次变 FINISHED；此时调用 `advanceWave()` 才原子地把下一波置为 ACTIVE。
3. **不能跳波、不能在上一波没结束时放开后面的波**：当前波还有设备未到终态时推进，
   返回 `advanced=false, reasonCode=WAVE_NOT_FINISHED`，系统状态不变。
4. 并发 / 重复推进安全：判定与状态切换在**同一把批次锁**内完成，同一波次只会被放开一次，
   其余调用拿到与“只推进一次”完全一致的结果（只有一个 `advanced=true`）。
5. 最后一波的最后一台设备到达终态后，若未熔断则整批直接 `COMPLETED`，无需再推进；
   对已 COMPLETED 的批次推进返回 `BATCH_COMPLETED`（幂等）。

每个波次可通过 `BatchSnapshot.waves()`（`List<WaveSnapshot>`）查到各自独立的
状态、设备数与统计计数（放量期与回滚后两套口径，见 4.5 节）。

### 暂停 / 恢复对所有波次生效

- `pause()` 后任何波次（含当前波）都不再接受进度上报（`BATCH_PAUSED`），推进也被拒绝；
  心跳照常接受。
- `resume()` 后从停下的当前波次接着走，波次序号与计数不变。

### 波次切换节骨眼与回滚后的迟到 / 重复上报

- 已结束波次的设备在切波之后补报：走与首次相同的幂等路径——
  与已接受去重键一致的重复上报返回 `duplicate=true`、不计数；终态不允许翻转。
- 批次熔断（`CIRCUIT_OPEN`）或回滚完成（`ROLLED_BACK`）后：
  仅放行与设备**回滚前最后一次已接受上报一致**的重复上报（呈现回滚前升级态、不再改版本/计数），
  其余进度上报一律拒绝。因此设备在切换节骨眼或回滚后补报/重复上报，
  结果与它只报一次完全一致，各波次计数不会多算或少算。

## 3. 兼容规则（允许进批的范围）

校验入口：`CompatibilityService.check(device, firmware)`，判定顺序固定、
原因互斥，每条拒绝都带有 `reason`（机器可判）和 `detail`（实际值 vs 期望值，即判定依据）：

| 顺序 | 拒绝原因 `RejectReason` | 判定依据 |
| --- | --- | --- |
| 1 | `MODEL_MISMATCH` | 设备型号 ≠ 固件目标型号 |
| 2 | `CURRENT_VERSION_TOO_LOW` | 设备当前版本 < 固件 `minFromVersion` |
| 3 | `DEPENDENCY_NOT_SATISFIED` | 固件要求的属性键缺失，或值不匹配 |

另有登记 / 归属类原因：`DEVICE_NOT_FOUND`、`FIRMWARE_NOT_FOUND`、
`DEVICE_ALREADY_REGISTERED`、`FIRMWARE_ALREADY_REGISTERED`、`DEVICE_NOT_IN_BATCH`。

- 版本号由 `VersionComparator` 按**点分数字段**比较（`1.10.0 > 1.9.0`），
  缺段补 0；出现非数字段时该段退化为字符串比较，不会抛异常。
- 建批时对**每台**设备做校验，任一不兼容则整批不创建，并抛出携带精确原因的
  `CompatibilityException`，不存在“部分设备进批”的情况。**分波次建批也不例外**：
  先逐台过兼容，再校验波次结构（空波/重复/覆盖性），任何一关不过整批都不会创建。

## 4. 回滚判定方式

### 4.1 熔断阈值

在**整批已结束设备**（所有波次的 SUCCEEDED + FAILED）中计算：

```
失败率 = failedCount / (succeededCount + failedCount)   （四舍五入取整百分比）
当 failedCount ≥ 1 且 失败率 ≥ 批次阈值 时立即熔断
```

以“已结束设备”而不是全量设备为分母，灰度早期（其余设备尚未上报）就能触发。
注意分母是**跨波次的整批已结束设备**：前面已经成功的波次会一起计入，
因此在第 N 波设置阈值时要把前 N-1 波的成功基数考虑进去
（例如前一波 3 台全成功后，本波要在阈值 50% 熔断需出现足够多的失败）。
纯成功永远不熔断。

### 4.2 原子熔断，无半推半退

失败上报触发熔断时，在**同一把批次锁**内完成三件事：
1) 应用 FAILED 迁移与计数；2) 采集全部设备（含所有波次）的快照 `RollbackCapture`；
3) 批次立即切到 `CIRCUIT_OPEN`，阻断之后的一切进度上报与波次推进。

设备版本恢复这类 I/O 在锁外执行，但快照已固定、新上报已被阻断；
每台设备的最终状态仍在批次锁内写回。因此不会出现“新升级混进回滚中”的中间状态。

### 4.3 三类设备分别处置（关键）

回滚逐台判定，结果可区分（`RollbackOutcome` 中分别计数）：

| 回滚前状态 | 处置 | 回滚后状态 | 统计字段 |
| --- | --- | --- | --- |
| `SUCCEEDED` | 设备已运行新固件，恢复到建批时快照的原版本 | 恢复成功 `ROLLBACK_SUCCEEDED`；恢复报错/超时则 `ROLLBACK_FAILED`（需人工介入） | `rolledBackSucceeded` / `failedDevices` |
| `NOT_STARTED` | 设备仍是原版本，**不做任何动作** | `ROLLBACK_SKIPPED` | `skippedNotStarted` |
| `IN_PROGRESS` / `FAILED` | 可能正刷写到一半或已失败，固件状态不确定，禁止盲目远程操作 | `ROLLBACK_FAILED` | `failedDevices` |

- 恢复失败**绝不写假成功**：版本不回写、状态明确标 `ROLLBACK_FAILED`。
- 回滚幂等：回滚完成后再次调用 `rollback()` 直接复现上次结果；
  若回滚整体执行到一半中断（批次仍是 `CIRCUIT_OPEN`），重试会重新采集快照，
  已是回滚终态的设备跳过，未处理设备继续处置，不会重复恢复也不会漏处理。

### 4.4 熔断回滚与波次推进的衔接

- **某一波触发熔断进入回滚后，后面还没开始的波次原地不动**（保持 PENDING），
  不会被悄悄放开；回滚完成后批次为 `ROLLED_BACK`，**不允许再推进波次**
  （`advanceWave()` 返回/抛出 `BATCH_ROLLED_BACK`）。
- **按波次分别给出处置结果**：`RollbackOutcome.waveSummaries()` 是
  `List<WaveRollbackSummary>`，每个波次各自给出总数 / 恢复成功 / 未开始跳过 /
  人工处置数；`RollbackDeviceResult` 也带 `waveIndex` 可定位设备属于哪一波。
  通常：已成功波次整波 `ROLLBACK_SUCCEEDED`，熔断波混合三类，未开始波次整波 `ROLLBACK_SKIPPED`。
- **回滚中断可续跑**：回滚逐台“锁外 I/O + 锁内提交”，中途异常只让本次调用抛出，
  批次停留 `CIRCUIT_OPEN`；重试时已提交回滚终态的设备不再恢复，未处理设备接着处理，
  最终收敛到 `ROLLED_BACK`，不留半推半退状态（恢复 I/O 不会对同一设备重复执行）。

### 4.5 回滚后各波次的状态与统计口径

**波次状态怎么变**（自动熔断回滚与人工回滚同一个入口，表现完全一致）：

- 回滚开始（批次切到 `CIRCUIT_OPEN`）的同一把锁内，波次状态即收敛：
  **已放开过的波次**（ACTIVE/FINISHED）一律变为 `ROLLED_BACK`；
  **从未放开的后续波次**保持 `PENDING`（未开始，原样没动，进度不被带跑）；
  `activeWaveIndex` 复位为 -1（不再存在“当前放开波次”）。
- 因此**回滚结束后不会再有任何波次显示“进行中”（ACTIVE）**；
  回滚中途被打断时波次也不会停在 ACTIVE，重试完成后状态与统计收敛到同一最终结果。
- 重复回滚、回滚过程中/之后并发查询批次：波次状态与计数稳定一致
  （状态收敛与计数都在批次锁内完成）。

**按波次能查到哪些统计**（`WaveSnapshot`，两套口径互不混算）：

| 时期 | 字段 | 含义 |
| --- | --- | --- |
| 放量期 | `succeededCount` / `failedCount` / `inProgressCount` / `notStartedCount` | 升级成功 / 失败 / 进行中 / 未开始，四者之和 = `totalDevices` |
| 回滚后 | `rolledBackCount` / `skippedCount` / `needsManualCount` | 被恢复回原版本 / 本来没开始被跳过 / 状态不明需人工，三者之和 = `totalDevices` |

- 回滚后升级期四个计数清零，**已回滚掉的设备不会被算进“未开始”**；
  每个波次的“恢复 + 跳过 + 人工”恰好等于该波设备总数。
- 按波次统计与整批回滚结果严格一致：`WaveSnapshot` 的回滚三口径逐波等于
  `RollbackOutcome.waveSummaries()` 的 `rolledBackSucceeded / skippedNotStarted /
  failedDevices`，各波之和等于整批 `RollbackOutcome` 的总计数。

## 5. 进度上报的幂等与并发

- 心跳（HEARTBEAT）任意状态下都接受，永不改状态、永不计数。
- 去重键：上报带 `eventId` 时以 `eventId` 为准，否则以阶段名（如 `phase:SUCCEEDED`）为准。
- 重复上报返回 `duplicate=true, accepted=false`，与只报一次的结果完全一致，
  成功/失败计数只增一次。
- 设备版本只在**首次接受 SUCCEEDED** 时推进到新版本。
- 所有计数变更都在批次对象锁内完成；16 线程 × 每设备 10 次重复成功上报
  外加大量心跳的并发测试中，成功计数始终恰好等于设备数。

## 6. 代码结构

```
model/        实体与状态机：Device / FirmwarePackage / Batch / BatchDeviceEntry /
              BatchStatus / DeviceUpgradeState / ProgressEvent / RollbackOutcome ...
              波次相关：Wave / WaveStatus / WaveSnapshot / WaveAdvanceResult / WaveRollbackSummary
compat/       VersionComparator、CompatibilityService、CompatibilityResult、RejectReason
registry/     DeviceRegistry、FirmwareRegistry（内存 ConcurrentHashMap）
rollout/      RolloutOrchestrator（总编排：波次门控/推进/暂停恢复/回滚衔接）、
              RollbackExecutor（逐台回滚、中断续跑）、RollbackOutcomeFactory（整批+按波次汇总）、
              DeviceRestoreOperator / LocalRestoreOperator（本地版本恢复）
exception/    OtaException / CompatibilityException / BatchStateException（携带 reasonCode）
```

## 7. 本地验证方法

前置：JDK 21+、Maven 3.9+（本仓库在 JDK 上以 source/target 21 编译）。

```bash
# 编译
mvn compile

# 运行全部单测（共 34 个 = 上一轮 30 个 + 回滚波次一致性 4 个，全部本地、无外部服务）
mvn test

# 只看某一类
mvn -Dtest=CompatibilityServiceTest       test   # 兼容性拒绝原因可区分
mvn -Dtest=RolloutHappyPathTest           test   # 正常推进 / 暂停恢复
mvn -Dtest=IdempotentAndConcurrentReportTest test # 重复上报幂等 / 并发计数
mvn -Dtest=CircuitBreakerRollbackTest     test   # 熔断自动回滚 / 三类设备 / 恢复失败 / 幂等
mvn -Dtest=EndToEndIntegrationTest        test   # 登记->拒绝->发布 端到端
# —— 本轮新增：分波次放量 ——
mvn -Dtest=WaveProgressionTest            test   # 波次拆分/只放开第一波/跳波防护/每波统计/非法拆分
mvn -Dtest=WaveConcurrencyTest            test   # 24 线程并发推进只放开一次 + 100 台/5 波并发上报计数
mvn -Dtest=WaveRollbackTest               test   # 跨波次熔断回滚/后续波次冻结/回滚中断续跑/整批暂停恢复
mvn -Dtest=WaveLateReportTest             test   # 波次切换节骨眼与回滚后的迟到/重复上报幂等
# —— 本轮新增：回滚后波次状态与统计一致性 ——
mvn -Dtest=WaveRollbackStatusConsistencyTest test # 自动熔断/人工/中断重试回滚后无波次进行中、
                                                  # 各波“恢复/跳过/人工”与整批结果一致、并发查询稳定
```

关键并发场景：`WaveConcurrencyTest` 用 100 台设备拆成 5 波、每波 20 台，
24 个线程在同一起跑线并发上报（每台 SUCCEEDED 重复 12 次并混入 100 个心跳/波），
上报阶段结束后再用 24 线程同时推进，断言每波成功数恰好为 20、整批 100、
4 次波次放开各只发生一次，最终 COMPLETED 且版本全部推进。

测试日志会打印关键输入（设备型号/版本/属性、固件要求、阈值、波次划分）与判定依据
（兼容/拒绝原因、状态迁移、失败率、波次序号、`advanced` 与否及原因码、回滚分类计数），
便于排查，例如：

```
advanceWave: batch=cc, advanced=true, reason=ADVANCED, wave 0 -> 1, batchStatus=ACTIVE
report rejected (wave not opened): ... deviceWave=2, activeWave=0   => WAVE_NOT_OPENED
auto rollback after circuit: ... waves=[WaveRollbackSummary[waveIndex=0, ...], ...]
```

### 编程式快速上手（分波次放量）

```java
DeviceRegistry devices = new DeviceRegistry();
FirmwareRegistry firmwares = new FirmwareRegistry();
RolloutOrchestrator orch = new RolloutOrchestrator(
        devices, firmwares, new CompatibilityService(),
        new RollbackExecutor(new LocalRestoreOperator()));

orch.registerFirmware(new FirmwarePackage(
        "fw-2.0", "BOX-A", "2.0.0", "1.5.0", Map.of("secure_boot", "on")));
orch.registerDevice(new Device("dev-1", "BOX-A", "1.6.0", Map.of("secure_boot", "on")));
// ... 登记 dev-2 / dev-3

// 按顺序拆两波；旧的 4 参 createBatch 等价于单波次
orch.createBatch("b1", "fw-2.0", List.of("dev-1", "dev-2", "dev-3"), 50,
        List.of(List.of("dev-1"), List.of("dev-2", "dev-3")));
orch.start("b1");                                  // 只放开第 0 波
orch.report(new ProgressEvent("b1", "dev-1", ProgressPhase.SUCCEEDED, null, "evt-001"));
WaveAdvanceResult r = orch.advanceWave("b1");      // 第 0 波全终态后才放开第 1 波
BatchSnapshot snap = orch.snapshot("b1");          // snap.waves() 查看每波各自进度
// ... dev-2/dev-3 上报；最后一波走完整批 COMPLETED
RollbackOutcome outcome = orch.rollback("b1");     // 人工/熔断回滚，幂等、可中断续跑
outcome.waveSummaries();                           // 按波次分别的处置结果
```

## 8. 设计取舍说明

- 全部数据保存在内存注册表中，重启即失效——这是题目“本地数据、不依赖外部服务”
  的刻意选择；持久化不在范围内。
- 波次推进采用**显式 `advanceWave()` 闸门**而不是“当前波结束自动放开下一波”：
  放量动作通常需要人工/外部策略确认。判定与切换在批次锁内原子完成，因此闸门
  既不会跳波，重复/并发推进也只有一次生效。
- 熔断失败率的分母是**整批已结束设备**（含前序成功波次），保持与上一版本一致；
  这让“整批灰度”视角的风险可控，代价是设置后续波次阈值时要考虑前序成功基数。
- 回滚中 `IN_PROGRESS` / `FAILED` 设备不做远程强制刷写而标记人工介入，
  是为了避免对状态不确定的设备二次刷写造成变砖；计数上归入 `failedDevices`。
- 回滚后对“与回滚前已接受上报一致的重复上报”按幂等放行（呈现回滚前升级态、
  不再改版本/计数），对新上报仍拒绝——既满足“迟到/重复上报与只报一次一致”，
  又不会让回滚后的批次被新状态污染。
- 批次 COMPLETED 后重复的成功上报按幂等成功返回（不抛错），与“重复上报必须和
  只上报一次结果一致”的要求保持一致。
