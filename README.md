# OTA 固件灰度发布编排（本地可运行）

一套纯本地内存实现的固件灰度发布编排能力：设备与固件包登记、兼容性校验、
按批次灰度放开、暂停 / 恢复 / 回滚、进度幂等上报、失败率超阈自动熔断回滚。
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
  `CompatibilityException`，不存在“部分设备进批”的情况。

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
compat/       VersionComparator、CompatibilityService、CompatibilityResult、RejectReason
registry/     DeviceRegistry、FirmwareRegistry（内存 ConcurrentHashMap）
rollout/      RolloutOrchestrator（总编排）、RollbackExecutor、
              DeviceRestoreOperator / LocalRestoreOperator（本地版本恢复）
exception/    OtaException / CompatibilityException / BatchStateException（携带 reasonCode）
```

## 6. 本地验证方法

前置：JDK 21+、Maven 3.9+（本仓库在 JDK 上以 source/target 21 编译）。

```bash
# 编译
mvn compile

# 运行全部单测（共 18 个，覆盖四类要求场景）
mvn test

# 只看某一类
mvn -Dtest=CompatibilityServiceTest       test   # 兼容性拒绝原因可区分
mvn -Dtest=RolloutHappyPathTest           test   # 正常推进 / 暂停恢复
mvn -Dtest=IdempotentAndConcurrentReportTest test # 重复上报幂等 / 并发计数
mvn -Dtest=CircuitBreakerRollbackTest     test   # 熔断自动回滚 / 三类设备 / 恢复失败 / 幂等
mvn -Dtest=EndToEndIntegrationTest        test   # 登记->拒绝->发布 端到端
```

测试日志会打印关键输入（设备型号/版本/属性、固件要求、阈值）与判定依据
（兼容/拒绝原因、状态迁移、失败率、回滚分类计数），便于排查。

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

## 7. 设计取舍说明

- 全部数据保存在内存注册表中，重启即失效——这是题目“本地数据、不依赖外部服务”
  的刻意选择；持久化不在范围内。
- 回滚中 `IN_PROGRESS` / `FAILED` 设备不做远程强制刷写而标记人工介入，
  是为了避免对状态不确定的设备二次刷写造成变砖；计数上归入 `failedDevices`。
- 批次 COMPLETED 后重复的成功上报按幂等成功返回（不抛错），与“重复上报必须和
  只上报一次结果一致”的要求保持一致。
