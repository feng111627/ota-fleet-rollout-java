# OTA 固件灰度发布编排（本地可运行）

一套纯本地内存实现的固件灰度发布编排能力：设备与固件包登记、兼容性校验、
按批次灰度放开、**分波次逐步放量**、暂停 / 恢复 / 回滚、进度幂等上报、
失败率超阈自动熔断回滚。
**不依赖数据库、消息队列或任何外部服务**，`mvn test` 即可完整验证。

## 1. 发布流程

```
登记固件包 ──┐
            ├──> 兼容性校验（逐台，区分拒绝原因）
登记设备  ──┘            │
                        ├─ 不兼容：按精确原因拒绝，整批不创建
                        └─ 全部兼容：创建批次（PENDING）
                                   │
                          start（放开推送，ACTIVE）
                                   │
        设备上报心跳 / 下载 / 安装 / 成功 / 失败（全部幂等）
                                   │
             ┌─────────────────────┼───────────────────────┐
             ▼                     ▼                         ▼
        pause() 暂停          全部 SUCCEEDED          失败率 ≥ 阈值
        resume() 恢复         → COMPLETED             → 熔断 CIRCUIT_OPEN
                                                     → 自动回滚 ROLLED_BACK
             任意可回滚状态下都可以人工 rollback()（幂等）
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

## 1.5 分波次逐步放量（Wave）

不想一次性把整批设备全放开时，可以在建批时把设备**按给定顺序**拆成若干个
有先后的波次（wave），逐波收口、逐波放开。

### 建批：两种拆分方式

| 入口 | 规则 |
| --- | --- |
| `createBatchWithWaveSizes(batchId, fw, deviceIds, 阈值, [2,3,1])` | 按**每波设备数**拆；元素必须为正、总和必须等于设备数，顺序即波次顺序 |
| `createBatchWithWaveCount(batchId, fw, deviceIds, 阈值, waveCount)` | 按**波数尽量均匀**拆；除以波数有余数时多出的设备依次补到**靠前**的波次（7 台 / 3 波 ⇒ 3,2,2） |

- 拆分严格保持 `deviceIds` 入参顺序，**不重排、不去重**；每台设备恰属于一个波次。
- 波数大于设备数、每波大小非正、总和不符等非法拆法直接抛
  `IllegalArgumentException`，批次不会被建立。
- 老的 `createBatch(...)` 保持不变，是“单波、启动即放开全部设备”的特例。

### 推进规则（`advanceWave(batchId)`）

```
start 后只放开第 0 波（PENDING → ACTIVE），其余波原地 PENDING
        │
   本波设备上报，全部拿到“终态”（SUCCEEDED/FAILED）
        │
   advanceWave()：在同一把批次锁内判定
        ├─ 当前波还有设备没终态      → 原地不动（advanced=false）
        ├─ 触发了熔断（批次非 ACTIVE）→ 拒绝推进，进入回滚
        ├─ 不是最后一波              → 当前波 FINISHED，下一波 ACTIVE
        └─ 已是最后一波              → 当前波 FINISHED，整批 COMPLETED
```

关键约束：

- **不能跳波**：只能从当前波推进到紧邻的下一波；后波在放开前一律 `PENDING`，
  其设备上报被拒绝（原因码 `WAVE_NOT_OPEN`）。
- **上一波不收口，后波不放行**：即使上一波设备都成功了，也必须显式调用
  `advanceWave()` 才会放开下一波；调用时若本波还有设备未终态则原地不动。
- **最后一波也走完（调用推进收口）整批才 COMPLETED**——波次模式下上报路径
  不会因为“最后一台成功”就提前把批次置完成。
- **并发/重复推进幂等**：所有判定在批次对象锁内完成，同一波次最多被真正推进一次。
  重复或多线程同时调用 `advanceWave()`，得到的状态与只调用一次完全一致
  （`WaveProgressOutcome.advanced()` 只有一个调用为 true，其余为幂等重入），
  不会漏波、跳波，各波成功/失败计数不被带偏。
- 暂停（`PAUSED`）、熔断（`CIRCUIT_OPEN`）、回滚完成（`ROLLED_BACK`）下
  都不能推进波次；回滚完成后该批次**永久禁止**再推进。

### 波次状态（`model.WaveStatus`）与进度查询

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 已建波但未放开；回滚后**从未放开过**的波也保持该状态（直观表示“原地未动”） |
| `ACTIVE` | 当前正在放量、接收本波上报 |
| `FINISHED` | 本波设备全部终态且未熔断，已正常收口 |
| `ROLLED_BACK` | 批次回滚后，曾经放开过的波次的终态 |

- `waveSnapshots(batchId)` 返回每波的 `WaveSnapshot`：状态、设备列表、
  total / succeeded / failed / notStarted / inProgress，以及回滚后的
  rolledBack / rollbackSkipped / rollbackFailed，各波统计独立可查。
- `BatchSnapshot` 额外带 `currentWaveNo`、`waveCount`、`waves`。

### 波次与上报、暂停/恢复、熔断回滚的衔接

- **上报门控**：只有“当前已放开波”里尚未终态的设备能上报。未放开后波报
  `WAVE_NOT_OPEN`，已收口前波报 `WAVE_NOT_CURRENT`。
- **波次切换节骨眼上的迟到/重复上报**：终态设备的同键重复上报**先于波次门控**
  幂等返回（`duplicate=true`），因此设备在所属波已不是当前波之后重放成功上报，
  结果与只报一次一致、计数不多算；异键/异阶段上报按“终态不可翻转”安全拒绝，
  不迁移、不计数、不抛异常。
- **暂停/恢复对所有波一起生效**：暂停后无论哪一波都不再接受进度上报
  （心跳除外，仍接受），也不能推进；恢复后从停下的当前波继续，波次顺序不变。
- **熔断与回滚**：失败率口径仍是**整批已结束设备**（见第 3 节），所以熔断可能
  发生在任意一波。某波熔断后：
  - 后面未开始的波次**原地保持 PENDING，绝不会被悄悄放开**；
  - 回滚对**所有波**统一处置：已放开波里升级成功的设备恢复到各自建批原版本，
    未开始设备跳过，状态不明（IN_PROGRESS/FAILED/恢复失败）设备标记人工；
  - 处置结果通过 `RollbackOutcome.waveResults()` **按波分别给出**
    （`WaveRollbackOutcome`：每波 rolledBack/skipped/failed 与逐设备结果）；
  - 回滚完成后批次 `ROLLED_BACK`，**禁止再推进波次**。
- **回滚中途报错/被打断的重试**：批次停在 `CIRCUIT_OPEN`，已处置设备保持回滚
  终态；再次 `rollback()` 时重新采集快照，已是终态的设备跳过（不重复恢复），
  未处理设备继续处置，最终全部收口、不留半推半退。可注入
  `RollbackAbortTrigger` 在指定设备处置前抛异常来复现“中途被打断”。
- **回滚之后的补报**：同键终态重复上报仍幂等返回；任何全新上报被
  `BATCH_ROLLED_BACK` 明确拒绝，计数与设备版本不再改变。

## 2. 兼容规则

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
  `CompatibilityException`，不存在“部分设备进批”的情况。波次批次
  （`createBatchWithWaveSizes / createBatchWithWaveCount`）沿用完全相同的
  逐台校验，只有全部设备都落在兼容范围内才会拆波、建批。

### 允许进批的兼容范围（汇总）

一台设备要进入某个固件包的批次（单波或波次都一样），必须**同时**满足：

1. 设备与固件包都已在本地登记表中（否则 `DEVICE_NOT_FOUND` / `FIRMWARE_NOT_FOUND`）；
2. `device.model` 与固件 `targetModel` 完全相等；
3. `device.currentVersion >= firmware.minFromVersion`（固件未设下限则不限制，
   即任何版本都可作为升级起点）；
4. 固件 `requiredAttributes` 中的每个键设备都具备，且值完全相等。

不满足任一条即按对应 `RejectReason` 精确拒绝，整批不创建。
设备在同一登记/同一批次中不重复出现；波次拆分要求设备总数与各波大小之和严格相等。

## 3. 回滚判定方式

### 3.1 熔断阈值

在**已结束设备**（SUCCEEDED + FAILED）中计算：

```
失败率 = failedCount / (succeededCount + failedCount)   （四舍五入取整百分比）
当 failedCount ≥ 1 且 失败率 ≥ 批次阈值 时立即熔断
```

以“已结束设备”而不是全量设备为分母，灰度早期（其余设备尚未上报）就能触发；
例如阈值 50% 时，1 台成功 + 1 台失败即熔断。纯成功永远不熔断。

### 3.2 原子熔断，无半推半退

失败上报触发熔断时，在**同一把批次锁**内完成三件事：
1) 应用 FAILED 迁移与计数；2) 采集全部设备的快照 `RollbackCapture`；
3) 批次立即切到 `CIRCUIT_OPEN`，阻断之后的一切进度上报。

设备版本恢复这类 I/O 在锁外执行，但快照已固定、新上报已被阻断；
每台设备的最终状态仍在批次锁内写回。因此不会出现“新升级混进回滚中”的中间状态。

### 3.3 三类设备分别处置（关键）

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

## 4. 进度上报的幂等与并发

- 心跳（HEARTBEAT）任意状态下都接受，永不改状态、永不计数。
- 去重键：上报带 `eventId` 时以 `eventId` 为准，否则以阶段名（如 `phase:SUCCEEDED`）为准。
- 重复上报返回 `duplicate=true, accepted=false`，与只报一次的结果完全一致，
  成功/失败计数只增一次。
- 设备版本只在**首次接受 SUCCEEDED** 时推进到新版本。
- 所有计数变更都在批次对象锁内完成；16 线程 × 每设备 10 次重复成功上报
  外加大量心跳的并发测试中，成功计数始终恰好等于设备数。

## 5. 代码结构

```
model/        实体与状态机：Device / FirmwarePackage / Batch / BatchDeviceEntry /
              BatchStatus / DeviceUpgradeState / ProgressEvent / RollbackOutcome ...
              波次：Wave / WaveStatus / WavePlanner / WaveSnapshot /
              WaveProgressOutcome / WaveRollbackOutcome
compat/       VersionComparator、CompatibilityService、CompatibilityResult、RejectReason
registry/     DeviceRegistry、FirmwareRegistry（内存 ConcurrentHashMap）
rollout/      RolloutOrchestrator（总编排，含建波/推进/查询）、RollbackExecutor、
              DeviceRestoreOperator / LocalRestoreOperator（本地版本恢复）、
              RollbackAbortTrigger（回滚中止钩子，复现中途打断）、RollbackWaveGrouper
exception/    OtaException / CompatibilityException / BatchStateException（携带 reasonCode）
```

## 6. 本地验证方法

前置：JDK 21+、Maven 3.9+（本仓库在 JDK 上以 source/target 21 编译）。

```bash
# 编译
mvn compile

# 运行全部单测（共 31 个，覆盖上一轮四类场景 + 本轮波次六类场景）
mvn test

# 只看某一类
mvn -Dtest=CompatibilityServiceTest       test   # 兼容性拒绝原因可区分
mvn -Dtest=RolloutHappyPathTest           test   # 正常推进 / 暂停恢复
mvn -Dtest=IdempotentAndConcurrentReportTest test # 重复上报幂等 / 并发计数
mvn -Dtest=CircuitBreakerRollbackTest     test   # 熔断自动回滚 / 三类设备 / 恢复失败 / 幂等
mvn -Dtest=EndToEndIntegrationTest        test   # 登记->拒绝->发布 端到端

# —— 本轮新增：波次能力 ——
mvn -Dtest=WavePlannerTest                test   # 波次拆分顺序 / 均匀分配 / 非法入参
mvn -Dtest=WaveProgressionTest            test   # 逐波推进 / 防跳波 / 各波统计
mvn -Dtest=WaveConcurrencyTest            test   # 60 设备 5 波，16 线程并发上报 + 重复推进幂等
mvn -Dtest=WavePauseResumeTest            test   # 暂停对所有波生效 / 恢复从停下的波继续
mvn -Dtest=WaveRollbackTest               test   # 跨波熔断回滚 / 后波冻结 / 中断重试 / 按波结果
mvn -Dtest=WaveLateReportTest             test   # 波次切换节骨眼与回滚后的迟到/重复上报
```

测试日志会打印关键输入（设备型号/版本/属性、固件要求、阈值、波次大小/波数）
与判定依据（兼容/拒绝原因、状态迁移、失败率、每次推进 advanced/currentWave/reason、
回滚分类与按波计数），便于排查。波次并发用例以 60 台设备、5 个波次、16 线程
同时重复上报并多线程重复推进，验证每波统计精确（每波成功数恰为本波设备数）、
同一波只推进一次、最终整批 COMPLETED 且每台设备版本都推进成功。

### 编程式快速上手

```java
DeviceRegistry devices = new DeviceRegistry();
FirmwareRegistry firmwares = new FirmwareRegistry();
RolloutOrchestrator orch = new RolloutOrchestrator(
        devices, firmwares, new CompatibilityService(),
        new RollbackExecutor(new LocalRestoreOperator()));

orch.registerFirmware(new FirmwarePackage(
        "fw-2.0", "BOX-A", "2.0.0", "1.5.0", Map.of("secure_boot", "on")));
orch.registerDevice(new Device("dev-1", "BOX-A", "1.6.0", Map.of("secure_boot", "on")));

CompatibilityResult r = orch.checkCompatibility("dev-1", "fw-2.0");
if (!r.compatible()) {
    throw new IllegalStateException(r.reason() + ": " + r.detail());
}

orch.createBatch("b1", "fw-2.0", List.of("dev-1"), 50); // 失败率阈值 50%
orch.start("b1");
orch.report(new ProgressEvent("b1", "dev-1", ProgressPhase.SUCCEEDED, null, "evt-001"));
orch.pause("b1");
orch.resume("b1");
RollbackOutcome outcome = orch.rollback("b1"); // 人工/熔断回滚，幂等
```

分波次逐步放量：

```java
List<String> deviceIds = List.of("d1", "d2", "d3", "d4", "d5", "d6");
// 按每波大小拆 [2,3,1]（也可用 createBatchWithWaveCount(..., 3) 按波数均分）
orch.createBatchWithWaveSizes("w1", "fw-2.0", deviceIds, 50, List.of(2, 3, 1));
orch.start("w1");                              // 只放开第 0 波（d1、d2）

// ……第 0 波设备上报直到全部终态……
orch.advanceWave("w1");                        // 收口第 0 波，放开第 1 波（d3~d5）
List<WaveSnapshot> waves = orch.waveSnapshots("w1"); // 查每波状态与统计
// ……第 1 波全部终态……
orch.advanceWave("w1");                        // 放开第 2 波（d6）
// ……d6 终态……
orch.advanceWave("w1");                        // 最后一波收口，整批 COMPLETED
```

## 7. 设计取舍说明

- 全部数据保存在内存注册表中，重启即失效——这是题目“本地数据、不依赖外部服务”
  的刻意选择；持久化不在范围内。
- 回滚中 `IN_PROGRESS` / `FAILED` 设备不做远程强制刷写而标记人工介入，
  是为了避免对状态不确定的设备二次刷写造成变砖；计数上归入 `failedDevices`。
- 批次 COMPLETED 后重复的成功上报按幂等成功返回（不抛错），与“重复上报必须和
  只上报一次结果一致”的要求保持一致。

- **波次是“设备顺序的视图”，不是第二份状态真值**：`Wave` 只持有批次内同一份
  `BatchDeviceEntry` 引用，波次统计实时扫描条目得出，整批仍只有 succeeded/failed
  一套计数，从根上避免“波计数”和“批计数”对不上。
- **整批完成只由 `advanceWave()` 收口触发**（波次模式），不在设备上报路径上
  自动 COMPLETED：这样“最后一波走完并确认收口”是一个显式、可同步、可重入的
  判定点，多线程并发上报与推进时不会出现“上报线程抢先完成、推进线程重复推进”。
- **熔断失败率仍按整批已结束设备计算**，而不是按波单独算，符合“灰度总失败率
  超阈即停”的语义；熔断后靠波次状态（已放开 ROLLED_BACK / 未放开 PENDING）表达
  影响范围，回滚结果再按波分组，既不改变既有熔断口径，又能逐波看清处置。
- **终态重复上报先于波次门控判定**：设备是否已经终态是比“它属于哪一波”更本质的
  事实，优先据此幂等，保证设备在波次切换、暂停、熔断、回滚等任意时间点重放/补报
  都与只报一次一致，不会因为时序不同而变成异常或多/少计数。
