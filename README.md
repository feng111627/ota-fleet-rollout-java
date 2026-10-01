# OTA 固件灰度发布编排（本地可运行）

一套纯本地、内存态的固件灰度发布编排能力：登记固件包与设备、兼容性校验、按批次分档灰度推送、
暂停/恢复/回滚、设备上报幂等与并发安全、失败率自动熔断回滚。不依赖数据库或任何外部中间件。

技术栈：Java 21 + Spring Boot 3.4 + JUnit 5，数据全部存放在内存 Repository 中。

## 1. 发布流程

```
登记固件/设备 ──> 建批次(逐个做兼容性校验，不兼容设备被拒并给出原因)
            ──> advance 放开第 1 档 ──> 设备上报 PROGRESS/SUCCESS/FAILED/TIMEOUT
            ──> advance 放开第 2 档 ... ──> 末档全部终态 => COMPLETED
                 │
                 ├─ pause / resume：暂停/恢复，只影响“是否放开下一档”，在途设备上报照常处理
                 └─ rollback / 自动熔断：进入回滚编排
```

- **档位（stage）**：严格递增的百分比列表，默认 `[10,50,100]`。每次 `advance` 放开下一档，
  按建批次时的设备顺序取 `ceil(总数 * 百分比)` 台，首次覆盖到的设备被下发（PENDING→IN_PROGRESS）。
  重复 `advance`/重复放开不会对同一台设备重复下发。
- **批次状态机**：`CREATED → IN_PROGRESS ⇄ PAUSED`；异常路径 `→ CIRCUIT_OPEN → ROLLED_BACK`；
  正常终态 `COMPLETED`。
- **设备状态机**：`PENDING → IN_PROGRESS → SUCCESS / FAILED / TIMEOUT`；
  回滚路径 `SUCCESS → ROLLING_BACK → ROLLED_BACK`；未开始设备回滚时标记 `NOT_UPGRADED`。

### 上报语义（幂等 + 并发安全）

- `PROGRESS`：进度心跳，可任意重复，仅更新进度，不参与计数。
- `SUCCESS` / `FAILED` / `TIMEOUT`：终态。**同一种终态重复上报与只上报一次结果完全一致**，
  只有首次流转会计入批次成功/失败/超时计数。
- 终态后再上报与之冲突的终态，返回 `ILLEGAL_TRANSITION` 拒绝，计数不变。
- 批次内所有状态流转与计数都在**同一把批次锁**内完成，多人/多端同时上报不会带偏计数。
- `ROLLBACK_DONE`：设备确认旧版本已恢复，重复确认幂等。

## 2. 兼容规则

建批次与 `/api/compatibility` 都按以下顺序短路判定，拒绝原因严格区分（见 `RejectReason`）：

| 顺序 | 条件 | 拒绝原因 |
|---|---|---|
| 1 | 设备型号 ≠ 固件目标型号 | `MODEL_MISMATCH` |
| 2 | 设备当前版本 < 固件要求的最低可升级版本 | `VERSION_TOO_LOW` |
| 3 | 设备缺少固件要求的任一依赖能力 | `DEPENDENCY_MISSING` |
| - | 设备/固件未登记 | `DEVICE_NOT_FOUND` / `FIRMWARE_NOT_FOUND` |

三条全部通过才放行；版本号采用语义化 `x.y.z` 逐级比较。建批次时不兼容设备**不会**进入批次，
而是在响应的 `rejectedDevices` 中逐台给出 `deviceId + reason + detail`，彼此原因可区分。

## 3. 熔断与回滚判定

### 自动熔断

每次收到 `FAILED`/`TIMEOUT` 后，在批次锁内重算：

```
终态样本 = 成功 + 失败 + 超时
失败率   = (失败 + 超时) / 终态样本
触发条件 = 终态样本 >= minSamplesForCircuit 且 失败率 > failureRateThreshold
```

默认阈值 `0.2`、最小样本 `3`（可在建批次时覆盖）。采用“严格大于”，失败率恰好等于阈值不熔断。

### 回滚如何区分设备（关键）

进入回滚（`CIRCUIT_OPEN`）时按设备当前状态分别处理，**不存在半推半退的中间状态**：

- 已升级成功（`SUCCESS`）：下发回滚包，置 `ROLLING_BACK`，等待 `ROLLBACK_DONE` 确认后变 `ROLLED_BACK`。
- 尚未开始（`PENDING`）：**不做任何下发**，直接标记 `NOT_UPGRADED`，保持升级前版本。
- 升级中途（`IN_PROGRESS`）：不贸然推回滚包（避免设备处于半刷写状态），保持在途，
  等其终态上报——之后成功则立即补回滚，失败/超时则以失败终态保留。
- 已失败/已超时/已回滚/未升级：维持原终态不变。
- 熔断期间迟到的 `SUCCESS` 也会被立刻纳入回滚；若批次此前已显示 `ROLLED_BACK`，
  会重新打开熔断态直到该设备回滚确认，杜绝“新固件仍在运行”的残留。
- 当不存在任何 `ROLLING_BACK` 设备时，批次才落到终态 `ROLLED_BACK`。
  手动 `rollback` 与自动熔断走同一条编排，重复触发幂等。

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/devices` | 登记设备 |
| GET  | `/api/devices` | 设备列表 |
| POST | `/api/devices/heartbeat` | 设备心跳 |
| POST | `/api/firmwares` | 登记固件包 |
| GET  | `/api/firmwares` | 固件列表 |
| GET  | `/api/compatibility?firmwareId=&deviceId=` | 单设备兼容性校验 |
| POST | `/api/batches` | 建批次（返回 batchId 与被拒设备） |
| GET  | `/api/batches` / `/api/batches/{id}` | 批次列表/详情（含计数与每台设备状态） |
| POST | `/api/batches/{id}/advance` | 放开下一档 |
| POST | `/api/batches/{id}/pause` / `/resume` | 暂停 / 恢复 |
| POST | `/api/batches/{id}/rollback` | 手动回滚 |
| POST | `/api/batches/{id}/reports` | 进度/终态/回滚完成上报 |

领域错误返回 HTTP 409，body 含 `reason`（机器可判定的枚举）、`description`、`detail`；
参数非法返回 HTTP 400。

## 5. 本地验证方法

### 运行单元测试

```bash
mvn test
```

覆盖：正常分档推进与完成、暂停/恢复、重复上报幂等、并发上报计数不被带偏、
四类可区分的兼容性拒绝、失败率熔断与区分回滚。测试与业务代码均用 SLF4J 打印关键输入与判定依据
（如设备型号/版本/能力、固件条件、失败率样本与阈值、每台设备的状态流转）。

### 启动后用 curl 端到端验证

```bash
mvn spring-boot:run        # 启动时自动灌入 1 个固件 + 4 台演示设备
```

```bash
# 兼容性：放行 / 版本过低 / 型号不符 / 缺依赖，原因各不相同
curl -s 'http://localhost:8080/api/compatibility?firmwareId=fw-sensor-2.0.0&deviceId=dev-a1'
curl -s 'http://localhost:8080/api/compatibility?firmwareId=fw-sensor-2.0.0&deviceId=dev-a2'
curl -s 'http://localhost:8080/api/compatibility?firmwareId=fw-sensor-2.0.0&deviceId=dev-b1'
curl -s 'http://localhost:8080/api/compatibility?firmwareId=fw-sensor-2.0.0&deviceId=dev-a3'

# 建批次（仅 dev-a1 准入，其余三台分别以三种原因被拒）
curl -s -X POST http://localhost:8080/api/batches \
  -H 'Content-Type: application/json' \
  -d '{"firmwareId":"fw-sensor-2.0.0",
       "deviceIds":["dev-a1","dev-a2","dev-b1","dev-a3"],
       "stagePercentages":[100],"failureRateThreshold":0.5,"minSamplesForCircuit":2}'

# 用上一步返回的 batchId 放开档位、上报
curl -s -X POST http://localhost:8080/api/batches/{batchId}/advance
curl -s -X POST http://localhost:8080/api/batches/{batchId}/reports \
  -H 'Content-Type: application/json' -d '{"deviceId":"dev-a1","phase":"SUCCESS","progress":100}'
curl -s http://localhost:8080/api/batches/{batchId}
```
