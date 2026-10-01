package com.github.feng111627.otafleetrolloutjava.service;

import com.github.feng111627.otafleetrolloutjava.api.CreateBatchRequest;
import com.github.feng111627.otafleetrolloutjava.api.ReportRequest;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.CompatibilityResult;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeStatus;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.RejectReason;
import com.github.feng111627.otafleetrolloutjava.model.ReportPhase;
import com.github.feng111627.otafleetrolloutjava.model.RejectedDevice;
import com.github.feng111627.otafleetrolloutjava.model.RolloutBatch;
import com.github.feng111627.otafleetrolloutjava.model.RolloutCreateResult;
import com.github.feng111627.otafleetrolloutjava.model.RolloutStage;
import com.github.feng111627.otafleetrolloutjava.repository.DeviceRepository;
import com.github.feng111627.otafleetrolloutjava.repository.FirmwareRepository;
import com.github.feng111627.otafleetrolloutjava.repository.RolloutRepository;
import com.github.feng111627.otafleetrolloutjava.service.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RolloutService {
    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);
    private static final List<Integer> DEFAULT_STAGES = List.of(10, 50, 100);
    private static final double DEFAULT_FAILURE_THRESHOLD = 0.2d;
    private static final int DEFAULT_MIN_SAMPLES = 3;

    private final RolloutRepository rolloutRepository;
    private final FirmwareRepository firmwareRepository;
    private final DeviceRepository deviceRepository;
    private final CompatibilityService compatibilityService;
    private final AtomicInteger batchSeq = new AtomicInteger();

    public RolloutService(RolloutRepository rolloutRepository, FirmwareRepository firmwareRepository,
                          DeviceRepository deviceRepository, CompatibilityService compatibilityService) {
        this.rolloutRepository = rolloutRepository;
        this.firmwareRepository = firmwareRepository;
        this.deviceRepository = deviceRepository;
        this.compatibilityService = compatibilityService;
    }

    public CompatibilityResult checkCompatibility(String firmwareId, String deviceId) {
        FirmwarePackage firmware = firmwareRepository.findById(firmwareId)
                .orElseThrow(() -> new DomainException(RejectReason.FIRMWARE_NOT_FOUND, "firmwareId=" + firmwareId));
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DomainException(RejectReason.DEVICE_NOT_FOUND, "deviceId=" + deviceId));
        return compatibilityService.check(device, firmware);
    }

    public RolloutCreateResult createBatch(CreateBatchRequest request) {
        FirmwarePackage firmware = firmwareRepository.findById(request.firmwareId())
                .orElseThrow(() -> new DomainException(RejectReason.FIRMWARE_NOT_FOUND,
                        "firmwareId=" + request.firmwareId()));

        List<String> accepted = new ArrayList<>();
        List<RejectedDevice> rejected = new ArrayList<>();
        for (String deviceId : request.deviceIds() == null ? List.<String>of() : request.deviceIds()) {
            Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
            if (deviceOpt.isEmpty()) {
                rejected.add(new RejectedDevice(deviceId, RejectReason.DEVICE_NOT_FOUND, "设备未登记"));
                continue;
            }
            CompatibilityResult result = compatibilityService.check(deviceOpt.get(), firmware);
            if (result.compatible()) {
                accepted.add(deviceId);
            } else {
                rejected.add(new RejectedDevice(deviceId, result.reason(), result.detail()));
            }
        }

        List<RolloutStage> stages = new ArrayList<>();
        List<Integer> percentages = request.stagePercentages() == null
                || request.stagePercentages().isEmpty()
                ? DEFAULT_STAGES : request.stagePercentages();
        int last = 0;
        for (int p : percentages) {
            if (p <= last || p > 100) {
                throw new IllegalArgumentException("档位百分比必须严格递增且在 (0,100] 内: " + percentages);
            }
            stages.add(new RolloutStage(p));
            last = p;
        }

        double threshold = request.failureRateThreshold() == null
                ? DEFAULT_FAILURE_THRESHOLD : request.failureRateThreshold();
        int minSamples = request.minSamplesForCircuit() == null
                ? DEFAULT_MIN_SAMPLES : request.minSamplesForCircuit();
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("失败率阈值必须在 (0,1] 内: " + threshold);
        }

        String batchId = "batch-" + batchSeq.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        RolloutBatch batch = new RolloutBatch(batchId, firmware.getId(), accepted, stages,
                threshold, minSamples, System.currentTimeMillis());
        for (String deviceId : accepted) {
            batch.getDevices().put(deviceId, new DeviceUpgradeState(deviceId));
        }
        batch.setStatus(BatchStatus.CREATED);
        rolloutRepository.save(batch);
        log.info("[建批次] batchId={}, firmwareId={}, 准入设备={}, 拒绝设备={} (原因分布={}), 档位={}, 熔断阈值={}, 最小样本={}",
                batchId, firmware.getId(), accepted.size(), rejected.size(), summarizeRejected(rejected),
                percentages, threshold, minSamples);
        return new RolloutCreateResult(batchId, rejected);
    }

    private List<String> summarizeRejected(List<RejectedDevice> rejected) {
        return rejected.stream().map(r -> r.deviceId() + ":" + r.reason()).toList();
    }

    public RolloutBatch get(String batchId) {
        return rolloutRepository.findById(batchId)
                .orElseThrow(() -> new DomainException(RejectReason.BATCH_NOT_FOUND, "batchId=" + batchId));
    }

    public List<RolloutBatch> list() {
        return rolloutRepository.findAll();
    }

    /** 放开下一个灰度档位，并向新覆盖到的设备下发推送。 */
    public RolloutBatch advance(String batchId) {
        RolloutBatch batch = get(batchId);
        synchronized (batch.getLock()) {
            if (batch.getStatus() == BatchStatus.PAUSED) {
                throw new DomainException(RejectReason.BATCH_NOT_ACTIVE,
                        "批次已暂停，需先 resume 才能放开下一档位");
            }
            if (batch.getStatus() == BatchStatus.CIRCUIT_OPEN
                    || batch.getStatus() == BatchStatus.ROLLED_BACK
                    || batch.getStatus() == BatchStatus.COMPLETED) {
                throw new DomainException(RejectReason.BATCH_NOT_ACTIVE,
                        "批次状态=" + batch.getStatus() + "，不允许放开档位");
            }
            int nextIndex = batch.getCurrentStageIndex() + 1;
            if (nextIndex >= batch.getStages().size()) {
                throw new DomainException(RejectReason.BATCH_NOT_ACTIVE, "已是最后档位，无可放开档位");
            }
            batch.setCurrentStageIndex(nextIndex);
            RolloutStage stage = batch.getStages().get(nextIndex);
            stage.setOpened(true);
            int targetCount = targetDeviceCount(batch, stage.getPercentage());
            int dispatched = 0;
            for (int i = 0; i < targetCount; i++) {
                String deviceId = batch.getOrderedDeviceIds().get(i);
                DeviceUpgradeState state = batch.getDevices().get(deviceId);
                // 仅放开尚未推送的设备；已在流程中的设备不重复下发，保证幂等
                if (!state.isPushDispatched()) {
                    state.setPushDispatched(true);
                    state.setStatus(DeviceUpgradeStatus.IN_PROGRESS);
                    state.setProgress(0);
                    state.setLastMessage("已下发固件 " + batch.getFirmwareId());
                    state.setUpdatedAt(System.currentTimeMillis());
                    dispatched++;
                }
            }
            batch.setStatus(BatchStatus.IN_PROGRESS);
            log.info("[放开档位] batchId={}, 档位={}%, 覆盖设备数={}, 本次新下发={}",
                    batchId, stage.getPercentage(), targetCount, dispatched);
            return batch;
        }
    }

    public RolloutBatch pause(String batchId) {
        RolloutBatch batch = get(batchId);
        synchronized (batch.getLock()) {
            if (batch.getStatus() != BatchStatus.IN_PROGRESS) {
                throw new DomainException(RejectReason.BATCH_NOT_ACTIVE,
                        "仅推进中的批次可暂停，当前状态=" + batch.getStatus());
            }
            batch.setStatus(BatchStatus.PAUSED);
            log.info("[批次暂停] batchId={} => PAUSED（停止放开新档位，在途设备上报照常处理）", batchId);
            return batch;
        }
    }

    public RolloutBatch resume(String batchId) {
        RolloutBatch batch = get(batchId);
        synchronized (batch.getLock()) {
            if (batch.getStatus() != BatchStatus.PAUSED) {
                throw new DomainException(RejectReason.BATCH_NOT_ACTIVE,
                        "仅暂停的批次可恢复，当前状态=" + batch.getStatus());
            }
            batch.setStatus(BatchStatus.IN_PROGRESS);
            log.info("[批次恢复] batchId={} => IN_PROGRESS", batchId);
            return batch;
        }
    }

    /** 手动回滚，语义与自动熔断回滚一致。 */
    public RolloutBatch rollback(String batchId) {
        RolloutBatch batch = get(batchId);
        synchronized (batch.getLock()) {
            if (batch.getStatus() == BatchStatus.ROLLED_BACK
                    || batch.getStatus() == BatchStatus.COMPLETED) {
                throw new DomainException(RejectReason.BATCH_NOT_ACTIVE,
                        "批次状态=" + batch.getStatus() + "，无需回滚");
            }
            log.info("[手动回滚] batchId={} 触发回滚", batchId);
            initiateRollback(batch, "手动触发");
            return batch;
        }
    }

    /**
     * 设备心跳/进度上报。关键保证：
     * - 相同终态重复上报：只在首次流转时计数，重复上报结果与首次一致（幂等）；
     * - 全部计数与流转在批次锁内完成，并发上报不会带偏计数；
     * - 失败/超时终态可能触发熔断，熔断回滚在同一把锁内完成，不存在半推半退。
     */
    public RolloutBatch report(String batchId, ReportRequest request) {
        RolloutBatch batch = get(batchId);
        ReportPhase phase;
        try {
            phase = ReportPhase.valueOf(request.phase() == null ? "" : request.phase().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainException(RejectReason.INVALID_PHASE, "phase=" + request.phase());
        }
        synchronized (batch.getLock()) {
            DeviceUpgradeState state = batch.getDevices().get(request.deviceId());
            if (state == null) {
                throw new DomainException(RejectReason.DEVICE_NOT_IN_BATCH,
                        "deviceId=" + request.deviceId() + ", batchId=" + batchId);
            }
            if (!state.isPushDispatched()) {
                // 档位未放开的设备不允许上报任何进度/终态
                throw new DomainException(RejectReason.STAGE_NOT_OPEN,
                        "deviceId=" + request.deviceId() + " 所在档位尚未放开");
            }

            switch (phase) {
                case PROGRESS -> handleProgress(batch, state, request);
                case SUCCESS -> handleSuccess(batch, state, request);
                case FAILED -> handleFailure(batch, state, request, false);
                case TIMEOUT -> handleFailure(batch, state, request, true);
                case ROLLBACK_DONE -> handleRollbackDone(batch, state, request);
            }
            return batch;
        }
    }

    private void handleProgress(RolloutBatch batch, DeviceUpgradeState state, ReportRequest request) {
        if (state.getStatus() != DeviceUpgradeStatus.IN_PROGRESS) {
            // 进度心跳只在升级中有意义；终态后的重复进度心跳直接忽略（幂等，不报错）
            log.info("[进度上报] batchId={}, deviceId={}, 当前状态={} => 忽略重复/过期进度",
                    batch.getId(), state.getDeviceId(), state.getStatus());
            return;
        }
        int progress = request.progress() == null ? state.getProgress()
                : Math.max(0, Math.min(100, request.progress()));
        state.setProgress(progress);
        state.setLastMessage(request.message());
        state.setUpdatedAt(System.currentTimeMillis());
        log.info("[进度上报] batchId={}, deviceId={}, progress={} => 已记录",
                batch.getId(), state.getDeviceId(), progress);
    }

    private void handleSuccess(RolloutBatch batch, DeviceUpgradeState state, ReportRequest request) {
        // 幂等：已成功的设备重复上报 SUCCESS，直接返回，不重复计数
        if (state.getStatus() == DeviceUpgradeStatus.SUCCESS
                || state.getStatus() == DeviceUpgradeStatus.ROLLING_BACK
                || state.getStatus() == DeviceUpgradeStatus.ROLLED_BACK) {
            log.info("[成功上报] batchId={}, deviceId={}, 当前状态={} => 重复上报，按首次结果处理（计数不变）",
                    batch.getId(), state.getDeviceId(), state.getStatus());
            return;
        }
        if (state.getStatus() != DeviceUpgradeStatus.IN_PROGRESS) {
            throw new DomainException(RejectReason.ILLEGAL_TRANSITION,
                    "deviceId=" + state.getDeviceId() + " 当前状态=" + state.getStatus() + "，不能上报 SUCCESS");
        }
        state.setStatus(DeviceUpgradeStatus.SUCCESS);
        state.setProgress(100);
        state.setLastMessage(request.message());
        state.setUpdatedAt(System.currentTimeMillis());
        state.setCounted(true);
        batch.setSuccessCount(batch.getSuccessCount() + 1);
        log.info("[成功上报] batchId={}, deviceId={} => SUCCESS，累计成功={}",
                batch.getId(), state.getDeviceId(), batch.getSuccessCount());

        // 熔断进行中或回滚已结束后才到达的成功上报，也要补发回滚，避免新固件残留
        if (batch.getStatus() == BatchStatus.CIRCUIT_OPEN
                || batch.getStatus() == BatchStatus.ROLLED_BACK) {
            if (batch.getStatus() == BatchStatus.ROLLED_BACK) {
                // 迟到成功：批次重新打开熔断态，直到该设备回滚确认
                batch.setStatus(BatchStatus.CIRCUIT_OPEN);
                log.info("[迟到成功] batchId={}, deviceId={} 在回滚结束后到达，重新打开熔断态",
                        batch.getId(), state.getDeviceId());
            }
            dispatchRollbackForDevice(batch, state, "批次熔断回滚中，成功后立即回滚");
            maybeFinishRollback(batch);
        } else {
            maybeComplete(batch);
        }
    }

    private void handleFailure(RolloutBatch batch, DeviceUpgradeState state, ReportRequest request,
                               boolean timeout) {
        DeviceUpgradeStatus terminal = timeout ? DeviceUpgradeStatus.TIMEOUT : DeviceUpgradeStatus.FAILED;
        // 幂等：相同终态重复上报不重复计数
        if (state.getStatus() == terminal) {
            log.info("[失败上报] batchId={}, deviceId={}, phase={} => 重复上报，按首次结果处理（计数不变）",
                    batch.getId(), state.getDeviceId(), terminal);
            return;
        }
        if (state.getStatus() != DeviceUpgradeStatus.IN_PROGRESS) {
            throw new DomainException(RejectReason.ILLEGAL_TRANSITION,
                    "deviceId=" + state.getDeviceId() + " 当前状态=" + state.getStatus()
                            + "，不能上报 " + terminal);
        }
        state.setStatus(terminal);
        state.setLastMessage(request.message());
        state.setUpdatedAt(System.currentTimeMillis());
        state.setCounted(true);
        if (timeout) {
            batch.setTimeoutCount(batch.getTimeoutCount() + 1);
        } else {
            batch.setFailedCount(batch.getFailedCount() + 1);
        }

        int terminals = batch.getSuccessCount() + batch.getFailedCount() + batch.getTimeoutCount();
        int failures = batch.getFailedCount() + batch.getTimeoutCount();
        double rate = terminals == 0 ? 0d : (double) failures / terminals;
        log.info("[失败上报] batchId={}, deviceId={} => {}，累计失败={}，超时={}，成功={}，终态样本={}，失败率={}/阈值={}",
                batch.getId(), state.getDeviceId(), terminal, batch.getFailedCount(),
                batch.getTimeoutCount(), batch.getSuccessCount(), terminals,
                String.format("%.2f", rate), batch.getFailureRateThreshold());

        if (shouldTripCircuit(batch, terminals, failures, rate)) {
            log.warn("[熔断判定] batchId={} 失败率 {} 超过阈值 {}（样本 {}>={}）=> 自动熔断并回滚",
                    batch.getId(), String.format("%.2f", rate), batch.getFailureRateThreshold(),
                    terminals, batch.getMinSamplesForCircuit());
            initiateRollback(batch, "失败率 " + String.format("%.2f", rate)
                    + " 超过阈值 " + batch.getFailureRateThreshold());
        }
    }

    private boolean shouldTripCircuit(RolloutBatch batch, int terminals, int failures, double rate) {
        if (batch.getStatus() == BatchStatus.CIRCUIT_OPEN
                || batch.getStatus() == BatchStatus.ROLLED_BACK) {
            return false;
        }
        return terminals >= batch.getMinSamplesForCircuit()
                && rate > batch.getFailureRateThreshold();
    }

    private void handleRollbackDone(RolloutBatch batch, DeviceUpgradeState state, ReportRequest request) {
        // 幂等：重复回滚确认不重复处理
        if (state.getStatus() == DeviceUpgradeStatus.ROLLED_BACK) {
            log.info("[回滚确认] batchId={}, deviceId={} => 重复确认，忽略（计数不变）",
                    batch.getId(), state.getDeviceId());
            return;
        }
        if (state.getStatus() != DeviceUpgradeStatus.ROLLING_BACK) {
            throw new DomainException(RejectReason.ILLEGAL_TRANSITION,
                    "deviceId=" + state.getDeviceId() + " 当前状态=" + state.getStatus()
                            + "，不能上报 ROLLBACK_DONE");
        }
        state.setStatus(DeviceUpgradeStatus.ROLLED_BACK);
        state.setLastMessage(request.message() == null ? "已回退到升级前版本" : request.message());
        state.setUpdatedAt(System.currentTimeMillis());
        log.info("[回滚确认] batchId={}, deviceId={} => ROLLED_BACK", batch.getId(), state.getDeviceId());
        maybeFinishRollback(batch);
    }

    /**
     * 执行回滚编排（锁内调用）：
     * - SUCCESS / ROLLING_BACK：已运行新固件，下发回滚包，等待 ROLLBACK_DONE 确认；
     * - IN_PROGRESS：中途中断，不能推回滚包（避免半推半退），保持 IN_PROGRESS，
     *   待其终态上报后由各自处理逻辑决定（成功即回滚，失败则维持失败态）；
     * - PENDING：尚未开始升级，直接标记 NOT_UPGRADED，不做任何下发；
     * - FAILED / TIMEOUT / ROLLED_BACK / NOT_UPGRADED：维持原状。
     */
    private void initiateRollback(RolloutBatch batch, String reason) {
        batch.setStatus(BatchStatus.CIRCUIT_OPEN);
        int toRollback = 0;
        int notStarted = 0;
        for (DeviceUpgradeState state : batch.getDevices().values()) {
            switch (state.getStatus()) {
                case SUCCESS -> {
                    dispatchRollbackForDevice(batch, state, reason);
                    toRollback++;
                }
                case PENDING -> {
                    state.setStatus(DeviceUpgradeStatus.NOT_UPGRADED);
                    state.setLastMessage("批次回滚：设备尚未开始升级，保持原版本");
                    state.setUpdatedAt(System.currentTimeMillis());
                    notStarted++;
                }
                case IN_PROGRESS, FAILED, TIMEOUT, ROLLING_BACK, ROLLED_BACK, NOT_UPGRADED -> {
                    // 见方法注释：在途设备等待终态，其余终态维持不变
                }
            }
        }
        log.info("[回滚编排] batchId={}, 原因={}, 需回退成功设备(累计)={}, 未开始升级标记 NOT_UPGRADED={}",
                batch.getId(), reason, countByStatus(batch, DeviceUpgradeStatus.ROLLING_BACK), notStarted);
        maybeFinishRollback(batch);
    }

    private void dispatchRollbackForDevice(RolloutBatch batch, DeviceUpgradeState state, String reason) {
        if (state.getStatus() == DeviceUpgradeStatus.ROLLING_BACK
                || state.getStatus() == DeviceUpgradeStatus.ROLLED_BACK) {
            return; // 回滚已下发，保证重复触发幂等
        }
        state.setStatus(DeviceUpgradeStatus.ROLLING_BACK);
        state.setLastMessage("已下发回滚包，原因=" + reason);
        state.setUpdatedAt(System.currentTimeMillis());
        log.info("[下发回滚] batchId={}, deviceId={} => ROLLING_BACK（新固件曾升级成功，需退回旧版本）",
                batch.getId(), state.getDeviceId());
    }

    private void maybeFinishRollback(RolloutBatch batch) {
        if (batch.getStatus() != BatchStatus.CIRCUIT_OPEN) {
            return;
        }
        boolean pendingRollback = batch.getDevices().values().stream()
                .anyMatch(s -> s.getStatus() == DeviceUpgradeStatus.ROLLING_BACK);
        if (!pendingRollback) {
            batch.setStatus(BatchStatus.ROLLED_BACK);
            log.info("[回滚完成] batchId={} => ROLLED_BACK，成功设备已全部退回，未开始设备保持原状",
                    batch.getId());
        }
    }

    private void maybeComplete(RolloutBatch batch) {
        if (batch.getStatus() != BatchStatus.IN_PROGRESS
                || batch.getCurrentStageIndex() < batch.getStages().size() - 1) {
            return;
        }
        boolean unfinished = batch.getDevices().values().stream()
                .anyMatch(s -> s.getStatus() == DeviceUpgradeStatus.PENDING
                        || s.getStatus() == DeviceUpgradeStatus.IN_PROGRESS);
        if (!unfinished) {
            batch.setStatus(BatchStatus.COMPLETED);
            log.info("[批次完成] batchId={} => COMPLETED，成功={}, 失败={}, 超时={}",
                    batch.getId(), batch.getSuccessCount(), batch.getFailedCount(), batch.getTimeoutCount());
        }
    }

    private int targetDeviceCount(RolloutBatch batch, int percentage) {
        int n = batch.getOrderedDeviceIds().size();
        return (int) Math.ceil(n * (percentage / 100.0d));
    }

    private long countByStatus(RolloutBatch batch, DeviceUpgradeStatus status) {
        return batch.getDevices().values().stream().filter(s -> s.getStatus() == status).count();
    }
}
