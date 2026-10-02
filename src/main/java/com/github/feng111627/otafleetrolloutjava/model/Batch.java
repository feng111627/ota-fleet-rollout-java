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

    /** 波次模式下的有序波次列表；单波（老行为）批次为 null */
    private List<Wave> waves;
    /** 当前放开的波次序号；批次启动前为 -1 */
    private int currentWaveNo = -1;

    public Batch(String batchId, String firmwareId, int failureRateThresholdPercent) {
        this(batchId, firmwareId, failureRateThresholdPercent, null);
    }

    /**
     * @param waves 非空且非空列表时按波次模式运行；null 表示单波（启动即放开全部设备）
     */
    public Batch(String batchId, String firmwareId, int failureRateThresholdPercent, List<Wave> waves) {
        this.batchId = batchId;
        this.firmwareId = firmwareId;
        if (failureRateThresholdPercent < 1 || failureRateThresholdPercent > 100) {
            throw new IllegalArgumentException(
                    "failureRateThresholdPercent must be within [1,100], got " + failureRateThresholdPercent);
        }
        this.failureRateThresholdPercent = failureRateThresholdPercent;
        if (waves != null) {
            if (waves.isEmpty()) {
                throw new IllegalArgumentException("waves must not be empty");
            }
            this.waves = new ArrayList<>(waves);
        }
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

    // ---------------- 波次 ----------------

    /** 是否为波次模式（建批时拆成了多个有先后的波次） */
    public boolean isWaveMode() { synchronized (this) { return waves != null; } }

    /** 波次总数；单波模式为 1（启动即全部放开） */
    public int waveCount() { synchronized (this) { return waves == null ? 1 : waves.size(); } }

    /** 当前放开的波次序号（0 起）；启动前为 -1，全部收口后指向最后一波 */
    public int getCurrentWaveNo() { synchronized (this) { return currentWaveNo; } }

    /** 某台设备所属波次号（0 起）；单波模式固定为 0，设备不在批次内返回 -1 */
    public int waveNumberOf(String deviceId) {
        synchronized (this) {
            if (waves == null) {
                return entries.containsKey(deviceId) ? 0 : -1;
            }
            for (Wave wave : waves) {
                for (BatchDeviceEntry entry : wave.getEntries()) {
                    if (entry.getDeviceId().equals(deviceId)) {
                        return wave.getWaveNo();
                    }
                }
            }
            return -1;
        }
    }

    /** 各波次只读统计快照（保持波次顺序） */
    public List<WaveSnapshot> waveSnapshots() {
        synchronized (this) {
            if (waves == null) {
                return List.of();
            }
            List<WaveSnapshot> result = new ArrayList<>(waves.size());
            for (Wave wave : waves) {
                result.add(wave.snapshot());
            }
            return result;
        }
    }

    /** 指定波次的只读统计快照 */
    public WaveSnapshot waveSnapshot(int waveNo) {
        synchronized (this) {
            if (waves == null || waveNo < 0 || waveNo >= waves.size()) {
                throw new BatchStateException("WAVE_NOT_FOUND",
                        "wave #" + waveNo + " not found in batch '" + batchId + "'");
            }
            return waves.get(waveNo).snapshot();
        }
    }

    // ---------------- 生命周期 ----------------

    /** 放开推送：PENDING/PAUSED -> ACTIVE；波次模式只放开第一波 */
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
        if (waves != null && status == BatchStatus.ACTIVE && currentWaveNo < 0) {
            currentWaveNo = 0;
            waves.get(0).setStatus(WaveStatus.ACTIVE);
        }
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

        // 终态（含回滚态）设备的任何上报都先于波次门控处理：同键重复幂等返回，
        // 异键/异阶段上报安全地拒绝翻转（不迁移、不计数、不抛异常）。
        // 这样设备在波次切换节骨眼上、或批次回滚之后的补报/重报，结果与只报一次完全一致，
        // 不会因“它所属的波已不是当前波”而莫名变成异常。
        if (before == DeviceUpgradeState.SUCCEEDED || before == DeviceUpgradeState.FAILED
                || before == DeviceUpgradeState.ROLLBACK_SUCCEEDED
                || before == DeviceUpgradeState.ROLLBACK_SKIPPED
                || before == DeviceUpgradeState.ROLLBACK_FAILED) {
            boolean sameKey = key.equals(entry.getLastAcceptedEventKey());
            if (sameKey) {
                return new BatchProgressOutcome(false, true, before, false,
                        "duplicate terminal report '" + key + "' ignored, state stays " + before);
            }
            return new BatchProgressOutcome(false, false, before, false,
                    "terminal state " + before + " reached, report '" + key + "' rejected (no flip)");
        }

        // 波次门控：只能给“当前已放开的波”里尚未终态的设备上报，
        // 未放开的后波（WAVE_NOT_OPEN）/已收口前波（WAVE_NOT_CURRENT）一律拒绝。
        if (waves != null) {
            int waveNo = waveNumberOfLocked(event.deviceId());
            if (waveNo != currentWaveNo) {
                Wave wave = waves.get(waveNo);
                String code = wave.getStatus() == WaveStatus.PENDING
                        ? "WAVE_NOT_OPEN" : "WAVE_NOT_CURRENT";
                throw new BatchStateException(code,
                        "device '" + event.deviceId() + "' belongs to wave #" + waveNo
                                + " (" + wave.getStatus() + "), current wave is #" + currentWaveNo
                                + ", report not accepted");
            }
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

    /**
     * 仅当本次上报是“设备终态上的同键重复上报”时返回幂等结果，否则返回 null。
     * 供编排层在批次非 ACTIVE（暂停/熔断/回滚）时识别迟到的重复补报：
     * 重复的原样幂等返回，任何新上报仍按批次状态拒绝。
     */
    public synchronized BatchProgressOutcome tryIdempotentTerminal(ProgressEvent event) {
        if (event.phase() == ProgressPhase.HEARTBEAT) {
            return null;
        }
        BatchDeviceEntry entry = entries.get(event.deviceId());
        DeviceUpgradeState state = entry.getState();
        boolean terminal = state == DeviceUpgradeState.SUCCEEDED
                || state == DeviceUpgradeState.FAILED
                || state == DeviceUpgradeState.ROLLBACK_SUCCEEDED
                || state == DeviceUpgradeState.ROLLBACK_SKIPPED
                || state == DeviceUpgradeState.ROLLBACK_FAILED;
        if (!terminal) {
            return null;
        }
        String key = event.eventId() != null && !event.eventId().isBlank()
                ? "id:" + event.eventId()
                : "phase:" + event.phase().name();
        if (!key.equals(entry.getLastAcceptedEventKey())) {
            return null;
        }
        return new BatchProgressOutcome(false, true, state, false,
                "duplicate terminal report '" + key + "' accepted idempotently while batch "
                        + this.status + ", state stays " + state);
    }

    private boolean thresholdReachedLocked() {
        int finished = succeededCount + failedCount;
        if (finished == 0 || failedCount == 0) {
            return false;
        }
        long rate = Math.round(failedCount * 100.0 / finished);
        return rate >= failureRateThresholdPercent;
    }

    /**
     * 单波批次（老行为）在全部设备都成功时即时置为 COMPLETED。
     * 波次模式下上报路径不自动收口——必须等最后一波设备全部终态后显式
     * {@link #advanceWave()} 才算整批完成，因此这里直接返回 false。
     */
    private boolean checkCompleted() {
        if (waves != null) {
            return false;
        }
        if (succeededCount == entries.size() && entries.size() > 0) {
            status = BatchStatus.COMPLETED;
            return true;
        }
        return false;
    }

    /** 调用方已持锁：设备所属波次号 */
    private int waveNumberOfLocked(String deviceId) {
        for (Wave wave : waves) {
            for (BatchDeviceEntry entry : wave.getEntries()) {
                if (entry.getDeviceId().equals(deviceId)) {
                    return wave.getWaveNo();
                }
            }
        }
        return -1;
    }

    public synchronized boolean allSucceeded() {
        return succeededCount == entries.size() && entries.size() > 0;
    }

    // ---------------- 波次推进 ----------------

    /**
     * 尝试从当前波推进到下一波。全部判定在同一把批次锁内完成，
     * 因此并发或重复触发时同一波次最多推进一次，不会漏波或跳波。
     *
     * <p>推进条件（全部满足才放开下一波）：
     * <ol>
     *   <li>批次处于 ACTIVE（暂停/熔断/回滚/完成都不允许推进）；</li>
     *   <li>当前波设备全部拿到终态（SUCCEEDED/FAILED）；</li>
     *   <li>当前波没有触发熔断（触发时批次已不在 ACTIVE，由条件 1 拦掉）。</li>
     * </ol>
     * 最后一波收口后批次置为 COMPLETED。重复调用得到的状态与只调用一次一致，
     * 通过 {@link WaveProgressOutcome#advanced()} 区分“本次真的推进了”。
     */
    public synchronized WaveProgressOutcome advanceWave() {
        if (waves == null) {
            throw new BatchStateException("BATCH_NOT_WAVED",
                    "batch '" + batchId + "' was created without waves; start releases all devices");
        }
        if (rollbackDone || status == BatchStatus.ROLLED_BACK) {
            return new WaveProgressOutcome(false, false, currentWaveNo,
                    "batch already rolled back, wave advancement forbidden", status);
        }
        if (status == BatchStatus.COMPLETED) {
            return new WaveProgressOutcome(false, true, currentWaveNo,
                    "all waves already finished, batch COMPLETED", status);
        }
        if (status != BatchStatus.ACTIVE) {
            throw new BatchStateException("BATCH_NOT_ACTIVE",
                    "batch '" + batchId + "' is " + status + ", cannot advance wave");
        }
        if (currentWaveNo < 0) {
            throw new BatchStateException("BATCH_NOT_STARTED",
                    "batch '" + batchId + "' not started yet");
        }

        Wave current = waves.get(currentWaveNo);
        if (current.getStatus() == WaveStatus.FINISHED) {
            // 并发/重复触发：推进已发生过，复现“推进后”的一致视图
            int next = Math.min(currentWaveNo, waves.size() - 1);
            boolean completed = status == BatchStatus.COMPLETED;
            return new WaveProgressOutcome(false, completed, next,
                    "wave #" + currentWaveNo + " already finished (idempotent re-entry)", status);
        }
        if (!current.allDevicesFinished()) {
            return new WaveProgressOutcome(false, false, currentWaveNo,
                    "wave #" + currentWaveNo + " has unfinished devices, stay", status);
        }

        // 真正推进：收口当前波
        current.setStatus(WaveStatus.FINISHED);
        if (currentWaveNo == waves.size() - 1) {
            status = BatchStatus.COMPLETED;
            return new WaveProgressOutcome(true, true, currentWaveNo,
                    "last wave #" + currentWaveNo + " finished, batch COMPLETED", status);
        }
        int nextNo = currentWaveNo + 1;
        waves.get(nextNo).setStatus(WaveStatus.ACTIVE);
        currentWaveNo = nextNo;
        return new WaveProgressOutcome(true, false, nextNo,
                "wave #" + (nextNo - 1) + " finished, wave #" + nextNo + " opened", status);
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

    /** 回滚执行完成：CIRCUIT_OPEN -> ROLLED_BACK（幂等标记）；波次状态一并迁移 */
    public synchronized void completeRollback() {
        status = BatchStatus.ROLLED_BACK;
        rollbackDone = true;
        if (waves != null) {
            // 已放开过的波（ACTIVE/FINISHED）标 ROLLED_BACK；从未放开的保持 PENDING，
            // 直观表示“后面的波次原地未动、没有被悄悄放开”。
            for (Wave wave : waves) {
                if (wave.getStatus() != WaveStatus.PENDING) {
                    wave.setStatus(WaveStatus.ROLLED_BACK);
                }
            }
        }
    }
}
