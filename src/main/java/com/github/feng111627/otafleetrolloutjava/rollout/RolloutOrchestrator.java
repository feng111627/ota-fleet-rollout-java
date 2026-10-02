package com.github.feng111627.otafleetrolloutjava.rollout;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityResult;
import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.compat.RejectReason;
import com.github.feng111627.otafleetrolloutjava.exception.BatchStateException;
import com.github.feng111627.otafleetrolloutjava.exception.CompatibilityException;
import com.github.feng111627.otafleetrolloutjava.model.Batch;
import com.github.feng111627.otafleetrolloutjava.model.BatchDeviceEntry;
import com.github.feng111627.otafleetrolloutjava.model.BatchProgressOutcome;
import com.github.feng111627.otafleetrolloutjava.model.BatchSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ProgressEvent;
import com.github.feng111627.otafleetrolloutjava.model.ProgressReportResult;
import com.github.feng111627.otafleetrolloutjava.model.ProgressPhase;
import com.github.feng111627.otafleetrolloutjava.model.RollbackCapture;
import com.github.feng111627.otafleetrolloutjava.model.RollbackDeviceResult;
import com.github.feng111627.otafleetrolloutjava.model.RollbackOutcome;
import com.github.feng111627.otafleetrolloutjava.registry.DeviceRegistry;
import com.github.feng111627.otafleetrolloutjava.registry.FirmwareRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 固件灰度发布编排器（纯本地内存实现，不依赖任何外部服务）。
 *
 * <p>负责：固件包/设备登记与兼容性校验、批次创建与暂停/恢复/回滚、
 * 设备心跳与升级进度的幂等处理、失败率超阈自动熔断与回滚。
 * 所有计数变更在 {@link Batch} 对象锁内完成，可承受并发重复上报。
 */
public class RolloutOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RolloutOrchestrator.class);

    private final DeviceRegistry deviceRegistry;
    private final FirmwareRegistry firmwareRegistry;
    private final CompatibilityService compatibilityService;
    private final RollbackExecutor rollbackExecutor;
    private final Map<String, Batch> batches = new ConcurrentHashMap<>();

    public RolloutOrchestrator(DeviceRegistry deviceRegistry,
                               FirmwareRegistry firmwareRegistry,
                               CompatibilityService compatibilityService,
                               RollbackExecutor rollbackExecutor) {
        this.deviceRegistry = deviceRegistry;
        this.firmwareRegistry = firmwareRegistry;
        this.compatibilityService = compatibilityService;
        this.rollbackExecutor = rollbackExecutor;
    }

    // ---------------- 登记 ----------------

    public Device registerDevice(Device device) {
        log.info("registerDevice input: deviceId={}, model={}, version={}, attrs={}",
                device.getDeviceId(), device.getModel(), device.getCurrentVersion(),
                device.getAttributes());
        return deviceRegistry.register(device);
    }

    public FirmwarePackage registerFirmware(FirmwarePackage firmware) {
        log.info("registerFirmware input: firmwareId={}, model={}, version={}, minFrom={}, requires={}",
                firmware.getFirmwareId(), firmware.getTargetModel(), firmware.getVersion(),
                firmware.getMinFromVersion(), firmware.getRequiredAttributes());
        return firmwareRegistry.register(firmware);
    }

    /**
     * 校验单台设备与固件包的兼容性，返回结构化结果（含精确拒绝原因与判定依据）。
     */
    public CompatibilityResult checkCompatibility(String deviceId, String firmwareId) {
        Device device = deviceRegistry.get(deviceId);
        FirmwarePackage firmware = firmwareRegistry.get(firmwareId);
        CompatibilityResult result = compatibilityService.check(device, firmware);
        log.info("checkCompatibility device={} firmware={} => compatible={}, reason={}, detail={}",
                deviceId, firmwareId, result.compatible(), result.reason(), result.detail());
        return result;
    }

    // ---------------- 批次管理 ----------------

    /**
     * 创建批次：固件必须存在；每台设备必须登记且兼容，任一不满足都按精确原因拒绝，
     * 整个批次不会部分创建。
     */
    public Batch createBatch(String batchId, String firmwareId, List<String> deviceIds,
                             int failureRateThresholdPercent) {
        log.info("createBatch input: batchId={}, firmwareId={}, devices={}, threshold={}%",
                batchId, firmwareId, deviceIds, failureRateThresholdPercent);
        FirmwarePackage firmware = firmwareRegistry.get(firmwareId);
        Batch batch = new Batch(batchId, firmwareId, failureRateThresholdPercent);
        for (String deviceId : deviceIds) {
            Device device = deviceRegistry.get(deviceId);
            CompatibilityResult result = compatibilityService.check(device, firmware);
            if (!result.compatible()) {
                log.warn("createBatch rejected: device={} reason={} detail={}",
                        deviceId, result.reason(), result.detail());
                throw new CompatibilityException(result.reason(),
                        "device '" + deviceId + "' incompatible: " + result.detail());
            }
            batch.putEntry(new BatchDeviceEntry(deviceId, device.getCurrentVersion()));
        }
        if (batches.putIfAbsent(batchId, batch) != null) {
            throw new BatchStateException("BATCH_ALREADY_EXISTS", "batch '" + batchId + "' already exists");
        }
        log.info("createBatch created: batchId={}, size={}", batchId, deviceIds.size());
        return batch;
    }

    /**
     * 创建波次批次：设备按 {@code waveSizes} 给定的每波数量顺序拆波，
     * 元素必须为正且总和等于设备数。启动后只放开第一波，后续靠 {@link #advanceWave} 推进。
     */
    public Batch createBatchWithWaveSizes(String batchId, String firmwareId, List<String> deviceIds,
                                          int failureRateThresholdPercent, List<Integer> waveSizes) {
        log.info("createBatchWithWaveSizes input: batchId={}, firmwareId={}, devices={}, threshold={}%, waveSizes={}",
                batchId, firmwareId, deviceIds, failureRateThresholdPercent, waveSizes);
        return createWavedBatch(batchId, firmwareId, deviceIds, failureRateThresholdPercent,
                ordered -> com.github.feng111627.otafleetrolloutjava.model.WavePlanner
                        .planBySizes(ordered, waveSizes));
    }

    /**
     * 创建波次批次：按波数尽量均匀拆分（保持顺序，余数补到靠前波次）。
     */
    public Batch createBatchWithWaveCount(String batchId, String firmwareId, List<String> deviceIds,
                                          int failureRateThresholdPercent, int waveCount) {
        log.info("createBatchWithWaveCount input: batchId={}, firmwareId={}, devices={}, threshold={}%, waveCount={}",
                batchId, firmwareId, deviceIds, failureRateThresholdPercent, waveCount);
        return createWavedBatch(batchId, firmwareId, deviceIds, failureRateThresholdPercent,
                ordered -> com.github.feng111627.otafleetrolloutjava.model.WavePlanner
                        .planByWaveCount(ordered, waveCount));
    }

    /**
     * 波次批次公共建批逻辑：先逐台兼容性校验并按给定顺序收集条目（任一不兼容整批不建），
     * 再用规划器把“有序条目”切成波次（条目是批次内同一份对象，波次只持引用）。
     */
    private Batch createWavedBatch(String batchId, String firmwareId, List<String> deviceIds,
                                   int failureRateThresholdPercent,
                                   java.util.function.Function<List<BatchDeviceEntry>, List<com.github.feng111627.otafleetrolloutjava.model.Wave>> planner) {
        FirmwarePackage firmware = firmwareRegistry.get(firmwareId);
        List<BatchDeviceEntry> ordered = new ArrayList<>(deviceIds.size());
        for (String deviceId : deviceIds) {
            Device device = deviceRegistry.get(deviceId);
            CompatibilityResult result = compatibilityService.check(device, firmware);
            if (!result.compatible()) {
                log.warn("createWavedBatch rejected: device={} reason={} detail={}",
                        deviceId, result.reason(), result.detail());
                throw new CompatibilityException(result.reason(),
                        "device '" + deviceId + "' incompatible: " + result.detail());
            }
            ordered.add(new BatchDeviceEntry(deviceId, device.getCurrentVersion()));
        }
        List<com.github.feng111627.otafleetrolloutjava.model.Wave> waves = planner.apply(ordered);
        Batch batch = new Batch(batchId, firmwareId, failureRateThresholdPercent, waves);
        for (BatchDeviceEntry entry : ordered) {
            batch.putEntry(entry);
        }
        if (batches.putIfAbsent(batchId, batch) != null) {
            throw new BatchStateException("BATCH_ALREADY_EXISTS", "batch '" + batchId + "' already exists");
        }
        log.info("createWavedBatch created: batchId={}, devices={}, waves={}",
                batchId, ordered.size(), waves.size());
        return batch;
    }

    /**
     * 推进到下一波。幂等且线程安全：重复/并发调用同一波次最多放开一次，
     * 不会跳波；当前波未收口时原地不动。
     */
    public com.github.feng111627.otafleetrolloutjava.model.WaveProgressOutcome advanceWave(String batchId) {
        Batch batch = getBatch(batchId);
        synchronized (batch) {
            com.github.feng111627.otafleetrolloutjava.model.WaveProgressOutcome outcome = batch.advanceWave();
            log.info("advanceWave: batch={}, advanced={}, completed={}, currentWave={}, status={}, reason={}",
                    batchId, outcome.advanced(), outcome.completed(),
                    outcome.currentWaveNo(), outcome.batchStatus(), outcome.reason());
            return outcome;
        }
    }

    /** 查询各波次进度与统计（保持波次顺序） */
    public List<com.github.feng111627.otafleetrolloutjava.model.WaveSnapshot> waveSnapshots(String batchId) {
        Batch batch = getBatch(batchId);
        return batch.waveSnapshots();
    }

    public BatchSnapshot start(String batchId) {
        Batch batch = getBatch(batchId);
        synchronized (batch) {
            batch.activate();
            log.info("batch started: {}", batchId);
            return snapshotLocked(batch);
        }
    }

    public BatchSnapshot pause(String batchId) {
        Batch batch = getBatch(batchId);
        synchronized (batch) {
            batch.pause();
            log.info("batch paused: {}", batchId);
            return snapshotLocked(batch);
        }
    }

    public BatchSnapshot resume(String batchId) {
        Batch batch = getBatch(batchId);
        synchronized (batch) {
            batch.resume();
            log.info("batch resumed: {}", batchId);
            return snapshotLocked(batch);
        }
    }

    /**
     * 人工回滚：幂等。回滚已完成时直接复现上次的处置结果。
     */
    public RollbackOutcome rollback(String batchId) {
        Batch batch = getBatch(batchId);
        RollbackCapture capture;
        synchronized (batch) {
            capture = batch.beginRollback();
        }
        if (capture == null) {
            log.info("rollback skipped (already done), reproducing outcome: {}", batchId);
            return reproduceRollbackOutcome(batch);
        }
        log.info("rollback begin: batchId={}, devices={}", batchId, capture.items().size());
        RollbackOutcome outcome = rollbackExecutor.execute(batch, deviceRegistry, capture);
        log.info("rollback finished: batchId={}, rolledBackSucceeded={}, skippedNotStarted={}, failedDevices={}",
                batchId, outcome.rolledBackSucceeded(), outcome.skippedNotStarted(), outcome.failedDevices());
        return outcome;
    }

    // ---------------- 上报 ----------------

    /**
     * 处理设备心跳 / 进度上报。
     *
     * <p>心跳在任意批次状态下都被接受（仅表示在线）；进度上报仅 ACTIVE 批次接受，
     * 暂停 / 熔断 / 回滚 / 已完成时按不同原因拒绝。重复上报返回 duplicate=true、
     * accepted=false，结果与只上报一次完全相同，且不影响批次计数。
     */
    public ProgressReportResult report(ProgressEvent event) {
        Batch batch = getBatch(event.batchId());
        BatchDeviceEntry entry = batch.getEntry(event.deviceId());
        if (entry == null) {
            throw new CompatibilityException(RejectReason.DEVICE_NOT_IN_BATCH,
                    "device '" + event.deviceId() + "' is not in batch '" + event.batchId() + "'");
        }

        // 心跳：任意状态接受，永不改状态、永不重复计数
        if (event.phase() == ProgressPhase.HEARTBEAT) {
            log.info("report heartbeat: batch={}, device={}, state={}",
                    event.batchId(), event.deviceId(), entry.getState());
            return new ProgressReportResult(false, false, entry.getState(),
                    batch.getStatus(), false, "heartbeat accepted");
        }

        BatchStatus statusAtReport;
        BatchProgressOutcome outcome;
        boolean circuitOpened = false;
        RollbackCapture capture = null;

        // 整个“判定 + 计数 + 熔断触发 + 快照采集”在同一把批次锁内完成，并发上报无法串扰
        synchronized (batch) {
            statusAtReport = batch.getStatus();

            // 非 ACTIVE 状态下，唯一被放行的是“同键终态重复补报”：设备在波次切换节骨眼上、
            // 批次暂停/熔断/回滚之后才补报或重复上报，结果都与只报一次完全一致。
            if (statusAtReport != BatchStatus.ACTIVE) {
                BatchProgressOutcome terminal = batch.tryIdempotentTerminal(event);
                if (terminal != null) {
                    log.info("late/duplicate terminal report on {} batch idempotently ignored: batch={}, device={}, state={}",
                            statusAtReport, event.batchId(), event.deviceId(), terminal.deviceState());
                    return new ProgressReportResult(terminal.accepted(), terminal.duplicate(),
                            terminal.deviceState(), statusAtReport, false, terminal.detail());
                }
                if (statusAtReport == BatchStatus.COMPLETED) {
                    // 保持上一轮行为：批次 COMPLETED 后重复成功上报按幂等成功处理
                    BatchProgressOutcome completed = batch.applyProgress(event);
                    return new ProgressReportResult(completed.accepted(), completed.duplicate(),
                            completed.deviceState(), BatchStatus.COMPLETED, false, completed.detail());
                }
                String code = switch (statusAtReport) {
                    case PAUSED -> "BATCH_PAUSED";
                    case PENDING -> "BATCH_NOT_STARTED";
                    case CIRCUIT_OPEN -> "BATCH_CIRCUIT_OPEN";
                    case ROLLED_BACK -> "BATCH_ROLLED_BACK";
                    case COMPLETED -> "BATCH_COMPLETED";
                    default -> "BATCH_NOT_ACCEPTING";
                };
                log.warn("report rejected: batch={}, device={}, status={}, code={}",
                        event.batchId(), event.deviceId(), statusAtReport, code);
                throw new BatchStateException(code,
                        "batch '" + event.batchId() + "' is " + statusAtReport + ", report not accepted");
            }

            outcome = batch.applyProgress(event);

            // 首次 SUCCEEDED：设备版本推进到新版本
            if (outcome.accepted() && outcome.deviceState() == DeviceUpgradeState.SUCCEEDED) {
                FirmwarePackage firmware = firmwareRegistry.get(batch.getFirmwareId());
                deviceRegistry.get(event.deviceId()).setCurrentVersion(firmware.getVersion());
            }

            if (outcome.accepted() && outcome.thresholdReached()) {
                // 阈值达到：同一次持锁内采集快照并切到 CIRCUIT_OPEN，立即阻断后续上报
                capture = batch.beginRollback();
                circuitOpened = true;
                log.warn("CIRCUIT OPEN: batch={}, device={}, succeeded={}, failed={}, threshold={}%",
                        event.batchId(), event.deviceId(),
                        batch.getSucceededCount(), batch.getFailedCount(),
                        batch.getFailureRateThresholdPercent());
            }
        }

        // 回滚 I/O 在批次锁外执行：快照已定、批次已熔断，不会有新状态混入；
        // 每台设备的最终状态仍由 RollbackExecutor 在批次锁内提交。
        RollbackOutcome rollbackOutcome = null;
        if (capture != null) {
            rollbackOutcome = rollbackExecutor.execute(batch, deviceRegistry, capture);
            log.warn("auto rollback after circuit: batch={}, rolledBackSucceeded={}, skipped={}, failed={}",
                    event.batchId(), rollbackOutcome.rolledBackSucceeded(),
                    rollbackOutcome.skippedNotStarted(), rollbackOutcome.failedDevices());
        }

        log.info("report handled: batch={}, device={}, phase={}, eventId={}, accepted={}, duplicate={}, state={}, status={}, detail={}",
                event.batchId(), event.deviceId(), event.phase(), event.eventId(),
                outcome.accepted(), outcome.duplicate(), outcome.deviceState(),
                batch.getStatus(), outcome.detail());

        return new ProgressReportResult(outcome.accepted(), outcome.duplicate(),
                outcome.deviceState(), batch.getStatus(), circuitOpened, outcome.detail());
    }

    // ---------------- 查询 ----------------

    public BatchSnapshot snapshot(String batchId) {
        Batch batch = getBatch(batchId);
        synchronized (batch) {
            return snapshotLocked(batch);
        }
    }

    private BatchSnapshot snapshotLocked(Batch batch) {
        return new BatchSnapshot(batch.getBatchId(), batch.getFirmwareId(), batch.getStatus(),
                batch.totalDevices(), batch.getSucceededCount(), batch.getFailedCount(),
                batch.getFailureRateThresholdPercent(), batch.isRollbackDone(),
                batch.snapshotEntries(),
                batch.getCurrentWaveNo(), batch.waveCount(), batch.waveSnapshots());
    }

    /** 回滚已完成时依据当前设备状态复现一份结果 */
    private RollbackOutcome reproduceRollbackOutcome(Batch batch) {
        List<RollbackDeviceResult> results = new ArrayList<>();
        int rolledBackSucceeded = 0;
        int skippedNotStarted = 0;
        int failedDevices = 0;
        for (BatchDeviceEntry entry : batch.snapshotEntries()) {
            DeviceUpgradeState s = entry.getState();
            results.add(new RollbackDeviceResult(
                    entry.getDeviceId(), entry.getOriginalVersion(), s, s));
            switch (s) {
                case ROLLBACK_SUCCEEDED -> rolledBackSucceeded++;
                case ROLLBACK_SKIPPED -> skippedNotStarted++;
                default -> failedDevices++;
            }
        }
        RollbackOutcome outcome = new RollbackOutcome(batch.getBatchId(), batch.getStatus(),
                rolledBackSucceeded, skippedNotStarted, failedDevices, results);
        return outcome.withWaveResults(RollbackWaveGrouper.group(batch, results));
    }

    private Batch getBatch(String batchId) {
        Batch batch = batches.get(batchId);
        if (batch == null) {
            throw new BatchStateException("BATCH_NOT_FOUND", "batch '" + batchId + "' not found");
        }
        return batch;
    }
}
