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
import com.github.feng111627.otafleetrolloutjava.model.WaveAdvanceResult;
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
    /**
     * 创建批次（旧接口，等价于“全部设备在唯一一个波次”的单波次批次，
     * start 后即放开全部设备，语义与上一版本完全一致）。
     */
    public Batch createBatch(String batchId, String firmwareId, List<String> deviceIds,
                             int failureRateThresholdPercent) {
        return createBatch(batchId, firmwareId, deviceIds, failureRateThresholdPercent, null);
    }

    /**
     * 创建“分波次放量”批次。
     *
     * @param waveDeviceIds 按<b>给定顺序</b>拆出的若干波次，每波一组设备 ID；
     *                      传 null 表示单波次（旧语义）。要求至少一波、每波非空、
     *                      恰好覆盖全部设备且无重复无遗漏，否则整批不创建。
     *                      设备按它在 deviceIds/waveDeviceIds 中的相对顺序保留。
     */
    public Batch createBatch(String batchId, String firmwareId, List<String> deviceIds,
                             int failureRateThresholdPercent, List<List<String>> waveDeviceIds) {
        log.info("createBatch input: batchId={}, firmwareId={}, devices={}, threshold={}%, waves={}",
                batchId, firmwareId, deviceIds, failureRateThresholdPercent,
                waveDeviceIds == null ? 1 : waveDeviceIds.size());
        FirmwarePackage firmware = firmwareRegistry.get(firmwareId);
        Batch batch = new Batch(batchId, firmwareId, failureRateThresholdPercent);

        // 计算每台设备所属波次下标：单波次（waveDeviceIds==null）时全部为波次 0。
        // 波次结构（空波/重复/覆盖性）必须在逐台兼容性校验之前先验证，整批要么合法创建要么不创建。
        Map<String, Integer> waveIndexById = resolveWaveIndex(batchId, deviceIds, waveDeviceIds);

        // entries 按设备入批顺序登记（LinkedHashMap），波次顺序由 initWaves 单独维护
        for (String deviceId : deviceIds) {
            Device device = deviceRegistry.get(deviceId);
            CompatibilityResult result = compatibilityService.check(device, firmware);
            if (!result.compatible()) {
                log.warn("createBatch rejected: device={} reason={} detail={}",
                        deviceId, result.reason(), result.detail());
                throw new CompatibilityException(result.reason(),
                        "device '" + deviceId + "' incompatible: " + result.detail());
            }
            batch.putEntry(new BatchDeviceEntry(
                    deviceId, device.getCurrentVersion(), waveIndexById.get(deviceId)));
        }
        if (batches.putIfAbsent(batchId, batch) != null) {
            throw new BatchStateException("BATCH_ALREADY_EXISTS", "batch '" + batchId + "' already exists");
        }

        if (waveDeviceIds != null) {
            // 在批次锁内做波次结构校验（覆盖性/无重复/无空波），不满足则整批不创建
            try {
                batch.initWaves(waveDeviceIds);
            } catch (RuntimeException e) {
                batches.remove(batchId, batch);
                throw e;
            }
            log.info("createBatch created (waves): batchId={}, size={}, waveSizes={}",
                    batchId, deviceIds.size(),
                    waveDeviceIds.stream().map(List::size).toList());
        } else {
            log.info("createBatch created: batchId={}, size={}", batchId, deviceIds.size());
        }
        return batch;
    }

    /**
     * 推进到下一波动：当前波全部设备到达终态（SUCCEEDED/FAILED）且未熔断才真正放开下一波；
     * 提前/重复/并发推进均安全——同一波次不会被推进两次，也不会漏波或跳波。
     * 重复/并发调用拿到的系统状态与只推进一次完全一致（只有一个调用 advanced=true）。
     */
    public WaveAdvanceResult advanceWave(String batchId) {
        Batch batch = getBatch(batchId);
        WaveAdvanceResult result;
        synchronized (batch) {
            result = batch.advanceWaveLocked();
        }
        log.info("advanceWave: batch={}, advanced={}, reason={}, wave {} -> {}, batchStatus={}, detail={}",
                batchId, result.advanced(), result.reasonCode(),
                result.previousWaveIndex(), result.activeWaveIndex(),
                result.batchStatus(), result.detail());
        return result;
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
     * <p>心跳在任意批次状态下都被接受（仅表示在线）；进度上报的放行规则：
     * <ul>
     *   <li>批次 ACTIVE 且设备属于<b>当前放开波次</b>：正常应用，全部在批次锁内完成；</li>
     *   <li>设备属于<b>还没放开的后续波次</b>：拒绝 {@code WAVE_NOT_OPENED}，不能提前放量；</li>
     *   <li>设备属于<b>已经结束的前序波次</b>（波次切换节骨眼上的补报/迟到上报）：
     *       走与首次相同的幂等路径——重复终态返回 duplicate=true 不计数，终态不翻转，
     *       结果与只上报一次完全一致，不会多算少算；</li>
     *   <li>批次 PAUSED：任何波次都不接受进度（{@code BATCH_PAUSED}）；</li>
     *   <li>批次 CIRCUIT_OPEN / ROLLED_BACK：只放行与设备上次已接受上报完全一致的重复上报
     *       （熔断回滚后的迟到重复上报，结果与只报一次一致），其余拒绝；</li>
     *   <li>批次 COMPLETED：重复上报按幂等返回，不作为错误。</li>
     * </ul>
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
            log.info("report heartbeat: batch={}, device={}, wave={}, state={}",
                    event.batchId(), event.deviceId(), entry.getWaveIndex(), entry.getState());
            return new ProgressReportResult(false, false, entry.getState(),
                    batch.getStatus(), false, "heartbeat accepted");
        }

        BatchProgressOutcome outcome;
        boolean circuitOpened = false;
        RollbackCapture capture = null;

        // 整个“判定 + 波次门控 + 计数 + 熔断触发 + 快照采集”在同一把批次锁内完成，并发无法串扰
        synchronized (batch) {
            BatchStatus statusAtReport = batch.getStatus();

            if (statusAtReport == BatchStatus.COMPLETED) {
                // 已完成批次：重复的成功/进度上报按幂等返回，不作为错误
                BatchProgressOutcome terminal = batch.applyProgress(event);
                log.info("report on COMPLETED batch treated idempotently: batch={}, device={}, duplicate={}, state={}",
                        event.batchId(), event.deviceId(), terminal.duplicate(), terminal.deviceState());
                return new ProgressReportResult(terminal.accepted(), terminal.duplicate(),
                        terminal.deviceState(), BatchStatus.COMPLETED, false, terminal.detail());
            }

            if (statusAtReport == BatchStatus.CIRCUIT_OPEN || statusAtReport == BatchStatus.ROLLED_BACK) {
                // 熔断中/回滚后：只放行与已接受上报一致的重复上报（迟到重复上报幂等）
                BatchProgressOutcome dup = batch.rollbackDuplicateOrNull(event);
                if (dup != null) {
                    log.info("report in rollback window treated as idempotent duplicate: batch={}, device={}, state={}",
                            event.batchId(), event.deviceId(), dup.deviceState());
                    return new ProgressReportResult(dup.accepted(), dup.duplicate(),
                            dup.deviceState(), batch.getStatus(), false, dup.detail());
                }
                String code = statusAtReport == BatchStatus.CIRCUIT_OPEN
                        ? "BATCH_CIRCUIT_OPEN" : "BATCH_ROLLED_BACK";
                log.warn("report rejected: batch={}, device={}, status={}, code={}",
                        event.batchId(), event.deviceId(), statusAtReport, code);
                throw new BatchStateException(code,
                        "batch '" + event.batchId() + "' is " + statusAtReport + ", report not accepted");
            }

            if (statusAtReport != BatchStatus.ACTIVE) {
                String code = switch (statusAtReport) {
                    case PAUSED -> "BATCH_PAUSED";
                    case PENDING -> "BATCH_NOT_STARTED";
                    default -> "BATCH_NOT_ACCEPTING";
                };
                log.warn("report rejected: batch={}, device={}, status={}, code={}",
                        event.batchId(), event.deviceId(), statusAtReport, code);
                throw new BatchStateException(code,
                        "batch '" + event.batchId() + "' is " + statusAtReport + ", report not accepted");
            }

            // ---- 批次 ACTIVE：波次门控 ----
            int activeWave = batch.getActiveWaveIndex();
            int deviceWave = entry.getWaveIndex();
            if (deviceWave > activeWave) {
                log.warn("report rejected (wave not opened): batch={}, device={}, deviceWave={}, activeWave={}",
                        event.batchId(), event.deviceId(), deviceWave, activeWave);
                throw new BatchStateException("WAVE_NOT_OPENED",
                        "device '" + event.deviceId() + "' is in wave " + deviceWave
                                + " but only wave " + activeWave + " is open in batch '"
                                + event.batchId() + "'");
            }
            if (deviceWave < activeWave) {
                // 波次切换节骨眼的补报/迟到上报：前序波次设备必然已到终态，
                // applyProgress 只可能返回 duplicate 或拒绝翻转，绝不改计数/状态。
                outcome = batch.applyProgress(event);
                log.info("late report for finished wave treated idempotently: batch={}, device={}, deviceWave={}, activeWave={}, duplicate={}, state={}",
                        event.batchId(), event.deviceId(), deviceWave, activeWave,
                        outcome.duplicate(), outcome.deviceState());
                return new ProgressReportResult(outcome.accepted(), outcome.duplicate(),
                        outcome.deviceState(), batch.getStatus(), false, outcome.detail());
            }

            outcome = batch.applyProgress(event);

            // 首次 SUCCEEDED：设备版本推进到新版本
            if (outcome.accepted() && outcome.deviceState() == DeviceUpgradeState.SUCCEEDED) {
                FirmwarePackage firmware = firmwareRegistry.get(batch.getFirmwareId());
                deviceRegistry.get(event.deviceId()).setCurrentVersion(firmware.getVersion());
            }

            if (outcome.accepted() && outcome.thresholdReached()) {
                // 阈值达到：同一次持锁内采集快照并切到 CIRCUIT_OPEN，立即阻断后续所有波次的上报
                capture = batch.beginRollback();
                circuitOpened = true;
                log.warn("CIRCUIT OPEN: batch={}, device={}, wave={}, succeeded={}, failed={}, threshold={}%",
                        event.batchId(), event.deviceId(), deviceWave,
                        batch.getSucceededCount(), batch.getFailedCount(),
                        batch.getFailureRateThresholdPercent());
            }
        }

        // 回滚 I/O 在批次锁外执行：快照已定、批次已熔断，后续波次原地不动；
        // 每台设备的最终状态仍由 RollbackExecutor 在批次锁内提交。
        if (capture != null) {
            RollbackOutcome rollbackOutcome = rollbackExecutor.execute(batch, deviceRegistry, capture);
            log.warn("auto rollback after circuit: batch={}, rolledBackSucceeded={}, skipped={}, failed={}, waves={}",
                    event.batchId(), rollbackOutcome.rolledBackSucceeded(),
                    rollbackOutcome.skippedNotStarted(), rollbackOutcome.failedDevices(),
                    rollbackOutcome.waveSummaries());
        }

        log.info("report handled: batch={}, device={}, wave={}, phase={}, eventId={}, accepted={}, duplicate={}, state={}, status={}, activeWave={}, detail={}",
                event.batchId(), event.deviceId(), entry.getWaveIndex(), event.phase(), event.eventId(),
                outcome.accepted(), outcome.duplicate(), outcome.deviceState(),
                batch.getStatus(), batch.getActiveWaveIndex(), outcome.detail());

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
                batch.getWaveCount(), batch.getActiveWaveIndex(), batch.waveSnapshotsLocked());
    }

    /** 回滚已完成时依据当前设备状态复现一份结果（含按波次汇总） */
    private RollbackOutcome reproduceRollbackOutcome(Batch batch) {
        List<RollbackDeviceResult> results = new ArrayList<>();
        for (BatchDeviceEntry entry : batch.snapshotEntries()) {
            DeviceUpgradeState s = entry.getState();
            results.add(new RollbackDeviceResult(
                    entry.getDeviceId(), entry.getOriginalVersion(), s, s, entry.getWaveIndex()));
        }
        return RollbackOutcomeFactory.build(batch, results);
    }

    /**
     * 解析每台设备的波次下标，并在登记任何设备之前先做波次结构校验：
     * 至少一波、每波非空、设备都来自 deviceIds、无重复、恰好覆盖全部设备。
     * waveDeviceIds 为 null 时按单波次处理。
     */
    private Map<String, Integer> resolveWaveIndex(String batchId, List<String> deviceIds,
                                                  List<List<String>> waveDeviceIds) {
        Map<String, Integer> indexById = new java.util.HashMap<>();
        if (waveDeviceIds == null) {
            for (String id : deviceIds) {
                indexById.put(id, 0);
            }
            return indexById;
        }
        if (waveDeviceIds.isEmpty()) {
            throw new BatchStateException("WAVES_EMPTY",
                    "batch '" + batchId + "' must define at least one wave");
        }
        java.util.Set<String> declared = new java.util.HashSet<>(deviceIds);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int w = 0; w < waveDeviceIds.size(); w++) {
            List<String> waveIds = waveDeviceIds.get(w);
            if (waveIds == null || waveIds.isEmpty()) {
                throw new BatchStateException("WAVE_EMPTY",
                        "batch '" + batchId + "' wave " + w + " must not be empty");
            }
            for (String id : waveIds) {
                if (!declared.contains(id)) {
                    throw new BatchStateException("WAVE_DEVICE_NOT_IN_BATCH",
                            "device '" + id + "' in wave " + w
                                    + " is not in the device list of batch '" + batchId + "'");
                }
                if (!seen.add(id)) {
                    throw new BatchStateException("WAVE_DEVICE_DUPLICATED",
                            "device '" + id + "' appears in more than one wave of batch '" + batchId + "'");
                }
                indexById.put(id, w);
            }
        }
        if (seen.size() != deviceIds.size()) {
            throw new BatchStateException("WAVES_NOT_COVERING_ALL_DEVICES",
                    "waves cover " + seen.size() + " devices but batch lists " + deviceIds.size()
                            + " devices in batch '" + batchId + "'");
        }
        return indexById;
    }

    private Batch getBatch(String batchId) {        Batch batch = batches.get(batchId);
        if (batch == null) {
            throw new BatchStateException("BATCH_NOT_FOUND", "batch '" + batchId + "' not found");
        }
        return batch;
    }
}
