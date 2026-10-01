package com.github.feng111627.otafleetrolloutjava.model;

import com.github.feng111627.otafleetrolloutjava.exception.BatchStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 灰度发布批次。
 *
 * <p>批次自身维护设备级状态机与计数；所有状态迁移方法都在对象锁（synchronized）
 * 内完成，保证并发上报时计数不被带偏。熔断与回滚通过“同一次持锁内捕获快照 +
 * 立即置为 CIRCUIT_OPEN”的方式衔接，阻断新上报，避免出现半推半退的中间状态。
 */
public class Batch {

    private final String batchId;
    private final String firmwareId;
    /** 0~100，失败百分比阈值，达到即熔断 */
    private final int failureRateThresholdPercent;
    private volatile BatchStatus status = BatchStatus.PENDING;

    private final Map<String, BatchDeviceEntry> entries = new java.util.LinkedHashMap<>();

    private int succeededCount;
    private int failedCount;
    private volatile boolean rollbackDone;

    public Batch(String batchId, String firmwareId, int failureRateThresholdPercent) {
        this.batchId = batchId;
        this.firmwareId = firmwareId;
        if (failureRateThresholdPercent < 1 || failureRateThresholdPercent > 100) {
            throw new IllegalArgumentException(
                    "failureRateThresholdPercent must be within [1,100], got " + failureRateThresholdPercent);
        }
        this.failureRateThresholdPercent = failureRateThresholdPercent;
    }

    public String getBatchId() { return batchId; }
    public String getFirmwareId() { return firmwareId; }
    public int getFailureRateThresholdPercent() { return failureRateThresholdPercent; }
    public BatchStatus getStatus() { return status; }
    public boolean isRollbackDone() { return rollbackDone; }

    public int getSucceededCount() { synchronized (this) { return succeededCount; } }
    public int getFailedCount() { synchronized (this) { return failedCount; } }
    public int totalDevices() { synchronized (this) { return entries.size(); } }

    public List<BatchDeviceEntry> snapshotEntries() {
        synchronized (this) { return new ArrayList<>(entries.values()); }
    }

    public void putEntry(BatchDeviceEntry entry) {
        synchronized (this) { entries.put(entry.getDeviceId(), entry); }
    }

    public BatchDeviceEntry getEntry(String deviceId) {
        synchronized (this) { return entries.get(deviceId); }
    }

    // ---------------- 生命周期 ----------------

    /** 放开推送：PENDING/PAUSED -> ACTIVE */
    public synchronized void activate() {
        if (status == BatchStatus.ACTIVE) {
            throw new BatchStateException("BATCH_ALREADY_ACTIVE",
                    "batch '" + batchId + "' is already ACTIVE");
        }
        if (status != BatchStatus.PENDING && status != BatchStatus.PAUSED) {
            throw new BatchStateException("BATCH_NOT_STARTABLE",
                    "batch '" + batchId + "' is " + status + ", cannot start/resume");
        }
        status = BatchStatus.ACTIVE;
    }

    /** 暂停：ACTIVE -> PAUSED */
    public synchronized void pause() {
        if (status == BatchStatus.PAUSED) {
            return; // 幂等
        }
        if (status != BatchStatus.ACTIVE) {
            throw new BatchStateException("BATCH_NOT_PAUSABLE",
                    "batch '" + batchId + "' is " + status + ", only ACTIVE batch can be paused");
        }
        status = BatchStatus.PAUSED;
    }

    /** 恢复：PAUSED -> ACTIVE */
    public synchronized void resume() {
        if (status == BatchStatus.ACTIVE) {
            return; // 幂等
        }
        if (status != BatchStatus.PAUSED) {
            throw new BatchStateException("BATCH_NOT_RESUMABLE",
                    "batch '" + batchId + "' is " + status + ", only PAUSED batch can be resumed");
        }
        status = BatchStatus.ACTIVE;
    }

    // ---------------- 进度上报 ----------------

    /**
     * 在锁内应用一次上报。调用方保证事件与设备记录非空、批次当前允许该阶段上报。
     *
     * <p>幂等规则：
     * <ul>
     *   <li>心跳 HEARTBEAT 永远幂等成功，accepted=false；</li>
     *   <li>非终态阶段（DOWNLOADING/INSTALLING）只允许状态向前推进，
     *       同一阶段或向后回退的重复上报被忽略，accepted=false；</li>
     *   <li>终态（SUCCEEDED/FAILED）按去重键（eventId 优先，否则 phase 名）去重，
     *       重复上报返回 duplicate=true，计数不再变化；</li>
     *   <li>SUCCEEDED 与 FAILED 互斥，终态后不允许翻转。</li>
     * </ul>
     */
    public synchronized BatchProgressOutcome applyProgress(ProgressEvent event) {
        BatchDeviceEntry entry = entries.get(event.deviceId());
        String key = event.eventId() != null && !event.eventId().isBlank()
                ? "id:" + event.eventId()
                : "phase:" + event.phase().name();

        if (event.phase() == ProgressPhase.HEARTBEAT) {
            return new BatchProgressOutcome(false, false, entry.getState(), false,
                    "heartbeat accepted (no state change)");
        }

        DeviceUpgradeState before = entry.getState();

        // 终态不可翻转 + 终态去重
        if (before == DeviceUpgradeState.SUCCEEDED || before == DeviceUpgradeState.FAILED) {
            boolean sameKey = key.equals(entry.getLastAcceptedEventKey());
            if (sameKey) {
                return new BatchProgressOutcome(false, true, before, false,
                        "duplicate terminal report '" + key + "' ignored, state stays " + before);
            }
            return new BatchProgressOutcome(false, false, before, false,
                    "terminal state " + before + " reached, report '" + key + "' rejected (no flip)");
        }

        // 非终态阶段：同一去重键的重复上报忽略；向前推进（下载->安装）接受
        if (event.phase() == ProgressPhase.DOWNLOADING || event.phase() == ProgressPhase.INSTALLING) {
            if (key.equals(entry.getLastAcceptedEventKey())) {
                return new BatchProgressOutcome(false, true, before, false,
                        "duplicate progress report '" + key + "' ignored");
            }
            entry.setState(DeviceUpgradeState.IN_PROGRESS);
            entry.setLastAcceptedEventKey(key);
            return new BatchProgressOutcome(true, false, DeviceUpgradeState.IN_PROGRESS, false,
                    "state moved " + before + " -> IN_PROGRESS (" + event.phase() + ")");
        }

        // SUCCEEDED
        if (event.phase() == ProgressPhase.SUCCEEDED) {
            if (key.equals(entry.getLastAcceptedEventKey())) {
                return new BatchProgressOutcome(false, true, before, false,
                        "duplicate SUCCEEDED report '" + key + "' ignored");
            }
            entry.setState(DeviceUpgradeState.SUCCEEDED);
            entry.setLastAcceptedEventKey(key);
            succeededCount++;
            boolean done = checkCompleted();
            return new BatchProgressOutcome(true, false, DeviceUpgradeState.SUCCEEDED, false,
                    "state moved " + before + " -> SUCCEEDED" + (done ? "; batch COMPLETED" : ""));
        }

        // FAILED
        if (key.equals(entry.getLastAcceptedEventKey())) {
            return new BatchProgressOutcome(false, true, before, false,
                    "duplicate FAILED report '" + key + "' ignored");
        }
        entry.setState(DeviceUpgradeState.FAILED);
        entry.setFailureMessage(event.message());
        entry.setLastAcceptedEventKey(key);
        failedCount++;
        return new BatchProgressOutcome(true, false, DeviceUpgradeState.FAILED,
                thresholdReachedLocked(),
                "state moved " + before + " -> FAILED"
                        + (thresholdReachedLocked() ? "; failure threshold reached" : ""));
    }

    /**
     * 失败率是否达到熔断阈值：已结束（成功+失败）的设备中失败占比 >= 阈值，
     * 且至少有 1 台失败。阈值在“首个失败发生时”即可能触发（小批次友好）。
     */
    public synchronized boolean thresholdReached() {
        return thresholdReachedLocked();
    }

    private boolean thresholdReachedLocked() {
        int finished = succeededCount + failedCount;
        if (finished == 0 || failedCount == 0) {
            return false;
        }
        long rate = Math.round(failedCount * 100.0 / finished);
        return rate >= failureRateThresholdPercent;
    }

    /** 全部设备都成功时批次置为 COMPLETED，返回是否发生了迁移 */
    private boolean checkCompleted() {
        if (succeededCount == entries.size() && entries.size() > 0) {
            status = BatchStatus.COMPLETED;
            return true;
        }
        return false;
    }

    public synchronized boolean allSucceeded() {
        return succeededCount == entries.size() && entries.size() > 0;
    }

    // ---------------- 熔断与回滚衔接 ----------------

    private static final Set<BatchStatus> ROLLBACKABLE = Set.of(
            BatchStatus.PENDING, BatchStatus.ACTIVE, BatchStatus.PAUSED,
            BatchStatus.CIRCUIT_OPEN, BatchStatus.COMPLETED);

    /**
     * 在同一把锁内：校验回滚条件、采集设备快照、立即将批次切到 CIRCUIT_OPEN。
     * 快照采集后新上报即被编排层阻断，执行器随后按快照恢复，保证无半推半退窗口。
     *
     * @return 回滚捕获快照；若回滚已完成则返回 null（调用方按幂等处理）
     */
    public synchronized RollbackCapture beginRollback() {
        if (rollbackDone) {
            return null;
        }
        if (!ROLLBACKABLE.contains(status)) {
            throw new BatchStateException("BATCH_NOT_ROLLBACKABLE",
                    "batch '" + batchId + "' is " + status + ", cannot rollback");
        }
        List<RollbackCaptureItem> items = new ArrayList<>(entries.size());
        for (BatchDeviceEntry entry : entries.values()) {
            items.add(new RollbackCaptureItem(entry, entry.getState(), entry.getOriginalVersion()));
        }
        status = BatchStatus.CIRCUIT_OPEN;
        return new RollbackCapture(items);
    }

    /** 回滚执行完成：CIRCUIT_OPEN -> ROLLED_BACK（幂等标记） */
    public synchronized void completeRollback() {
        status = BatchStatus.ROLLED_BACK;
        rollbackDone = true;
    }
}
