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

    /** 有序波次；旧建批路径在首次需要时退化为单波次 */
    private final List<Wave> waves = new ArrayList<>();
    /** 当前放开（ACTIVE）的波次下标；无放开波次时为 -1 */
    private int activeWaveIndex = -1;

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

    /**
     * 放开推送：PENDING/PAUSED -> ACTIVE。
     *
     * <p>分波次语义：start 只放开第一个波次（wave 0 ACTIVE，其余波次原地 PENDING）；
     * 旧的“无波次”建批路径在此刻惰性退化为单波次，行为与历史版本完全一致。
     */
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
        if (activeWaveIndex == -1) {
            ensureWavesLocked();
            activeWaveIndex = 0;
            waves.get(0).setStatus(WaveStatus.ACTIVE);
        }
    }

    /** 旧建批路径没有显式波次：惰性构造一个包含全部设备的单波次（全机波次 0） */
    private void ensureWavesLocked() {
        if (waves.isEmpty()) {
            waves.add(new Wave(0, new ArrayList<>(entries.keySet())));
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
            String waveNote = onTerminalLocked(entry);
            return new BatchProgressOutcome(true, false, DeviceUpgradeState.SUCCEEDED, false,
                    "state moved " + before + " -> SUCCEEDED" + waveNote);
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
        boolean threshold = thresholdReachedLocked();
        String waveNote = threshold ? "" : onTerminalLocked(entry);
        return new BatchProgressOutcome(true, false, DeviceUpgradeState.FAILED,
                threshold,
                "state moved " + before + " -> FAILED"
                        + (threshold ? "; failure threshold reached" : waveNote));
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

    /**
     * 一台设备在当前放开波次内到达终态（SUCCEEDED/FAILED）后的波次收敛：
     * 若这是该波最后一台未结束设备，则该波 ACTIVE -> FINISHED；
     * 并且仅当它是<b>最后一个波次</b>且本批次没有触发熔断时，整个批次收尾为 COMPLETED。
     *
     * <p>非最后一波结束后批次保持 ACTIVE，等待显式 {@link #advanceWaveLocked()}，
     * 因此不会“自动跳到下一波”，也不会在上一波未结束时放开后面的波次。
     * 返回附加到上报结果 detail 的说明文本。
     */
    private String onTerminalLocked(BatchDeviceEntry entry) {
        int wi = entry.getWaveIndex();
        if (wi != activeWaveIndex || wi < 0 || wi >= waves.size()) {
            return "";
        }
        Wave wave = waves.get(wi);
        if (wave.getStatus() != WaveStatus.ACTIVE || !isWaveAllTerminalLocked(wave)) {
            return "";
        }
        wave.setStatus(WaveStatus.FINISHED);
        if (wi == waves.size() - 1 && !thresholdReachedLocked()) {
            status = BatchStatus.COMPLETED;
            return "; wave " + wi + " FINISHED; batch COMPLETED";
        }
        return "; wave " + wi + " FINISHED (await advanceWave)";
    }

    private boolean isWaveAllTerminalLocked(Wave wave) {
        for (String id : wave.getDeviceIds()) {
            DeviceUpgradeState s = entries.get(id).getState();
            if (s != DeviceUpgradeState.SUCCEEDED && s != DeviceUpgradeState.FAILED) {
                return false;
            }
        }
        return true;
    }

    /** 波次内已结束（成功/失败）设备计数，用于判定是否可以推进 */
    private int waveFinishedCountLocked(Wave wave) {
        int n = 0;
        for (String id : wave.getDeviceIds()) {
            DeviceUpgradeState s = entries.get(id).getState();
            if (s == DeviceUpgradeState.SUCCEEDED || s == DeviceUpgradeState.FAILED) {
                n++;
            }
        }
        return n;
    }

    /**
     * 全部设备都成功（兼容旧查询/旧语义）。
     */
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
        // 波次冻结（与状态切换同一把锁）：曾放开过的波次（ACTIVE/FINISHED）随回滚开始
        // 即标记 ROLLED_BACK——此后任何时刻查批次都不会再有波次显示“正在放量/进行中”；
        // 始终没轮到的波次保持 PENDING 原样不动，进度不被带跑。
        // 中断重试会再次走到这里，已冻结的波次状态不变，天然幂等。
        for (Wave wave : waves) {
            if (wave.getStatus() == WaveStatus.ACTIVE || wave.getStatus() == WaveStatus.FINISHED) {
                wave.setStatus(WaveStatus.ROLLED_BACK);
            }
        }
        activeWaveIndex = -1;
        return new RollbackCapture(items);
    }

    /** 回滚执行完成：CIRCUIT_OPEN -> ROLLED_BACK（幂等标记） */
    public synchronized void completeRollback() {
        status = BatchStatus.ROLLED_BACK;
        rollbackDone = true;
    }

    // ---------------- 波次 ----------------

    /**
     * 建批后灌入有序波次。每个波次是一组设备 ID 的有序列表，要求：
     * <ul>
     *   <li>至少 1 个波次，每个波次至少 1 台设备；</li>
     *   <li>恰好覆盖全部已加入批次的设备，无重复、无遗漏、无批次外设备。</li>
     * </ul>
     * 必须在 start 之前调用一次；已灌入后再次调用按非法顺序拒绝。
     */
    public synchronized void initWaves(List<List<String>> waveDeviceIds) {
        if (!waves.isEmpty()) {
            throw new BatchStateException("WAVES_ALREADY_INITIALIZED",
                    "batch '" + batchId + "' waves already initialized");
        }
        if (waveDeviceIds == null || waveDeviceIds.isEmpty()) {
            throw new BatchStateException("WAVES_EMPTY",
                    "batch '" + batchId + "' must define at least one wave");
        }
        // 先全部构建到临时列表，校验通过后再一次性提交，保证本方法原子（不留半成品波次）
        List<Wave> parsed = new ArrayList<>(waveDeviceIds.size());
        Set<String> seen = new java.util.HashSet<>();
        int idx = 0;
        for (List<String> waveIds : waveDeviceIds) {
            if (waveIds == null || waveIds.isEmpty()) {
                throw new BatchStateException("WAVE_EMPTY",
                        "batch '" + batchId + "' wave " + idx + " must not be empty");
            }
            for (String id : waveIds) {
                if (!entries.containsKey(id)) {
                    throw new BatchStateException("WAVE_DEVICE_NOT_IN_BATCH",
                            "device '" + id + "' in wave " + idx + " is not in batch '" + batchId + "'");
                }
                if (!seen.add(id)) {
                    throw new BatchStateException("WAVE_DEVICE_DUPLICATED",
                            "device '" + id + "' appears in more than one wave of batch '" + batchId + "'");
                }
            }
            parsed.add(new Wave(idx, waveIds));
            idx++;
        }
        if (seen.size() != entries.size()) {
            throw new BatchStateException("WAVES_NOT_COVERING_ALL_DEVICES",
                    "waves cover " + seen.size() + " devices but batch has " + entries.size()
                            + " devices in batch '" + batchId + "'");
        }
        waves.addAll(parsed);
    }

    public int getActiveWaveIndex() { synchronized (this) { return activeWaveIndex; } }

    /** 波次总数；尚未初始化（未 start 的旧路径）时视作 1 个波次 */
    public int getWaveCount() { synchronized (this) { return Math.max(waves.size(), 1); } }

    public List<Wave> snapshotWaves() { synchronized (this) { return new ArrayList<>(waves); } }

    /**
     * 推进到下一波动（必须在批次锁内调用）。
     *
     * <p>推进规则：
     * <ul>
     *   <li>批次非 ACTIVE：按状态给出原因码；PAUSED/PENDING/CIRCUIT_OPEN/ROLLED_BACK 抛
     *       {@link BatchStateException}，COMPLETED 幂等返回；</li>
     *   <li>当前波还有设备未到终态：不推进，返回 advanced=false / WAVE_NOT_FINISHED，
     *       因而“不能跳波、不能在上一波未结束时放开后面的波”；</li>
     *   <li>当前波已全部到终态且不是最后一波：原子地把当前波置 FINISHED、下一波置 ACTIVE；
     *       并发/重复调用只有一个能赢，其余拿到的系统状态与只推进一次完全相同；</li>
     *   <li>当前波已是最后一波（且已 FINISHED，批次通常已 COMPLETED）：幂等返回 ALREADY_LAST_WAVE。</li>
     * </ul>
     */
    public synchronized WaveAdvanceResult advanceWaveLocked() {
        if (status == BatchStatus.COMPLETED) {
            return new WaveAdvanceResult(false, "BATCH_COMPLETED", activeWaveIndex, activeWaveIndex,
                    status, "batch '" + batchId + "' is COMPLETED, no wave to advance");
        }
        if (status == BatchStatus.PAUSED) {
            throw new BatchStateException("BATCH_PAUSED",
                    "batch '" + batchId + "' is PAUSED, resume before advancing waves");
        }
        if (status == BatchStatus.PENDING) {
            throw new BatchStateException("BATCH_NOT_STARTED",
                    "batch '" + batchId + "' is not started, start before advancing waves");
        }
        if (status == BatchStatus.CIRCUIT_OPEN) {
            throw new BatchStateException("BATCH_CIRCUIT_OPEN",
                    "batch '" + batchId + "' is in rollback, waves are frozen");
        }
        if (status == BatchStatus.ROLLED_BACK) {
            throw new BatchStateException("BATCH_ROLLED_BACK",
                    "batch '" + batchId + "' is rolled back, waves are frozen");
        }

        ensureWavesLocked();
        int current = activeWaveIndex < 0 ? 0 : activeWaveIndex;
        Wave wave = waves.get(current);

        if (wave.getStatus() == WaveStatus.PENDING) {
            // 极端竞态下（理论不可达，因推进必须经过 ACTIVE 波）保守按未结束处理，不放开
            throw new BatchStateException("BATCH_NOT_STARTED",
                    "wave " + current + " of batch '" + batchId + "' is not active");
        }

        if (wave.getStatus() == WaveStatus.ACTIVE) {
            int finished = waveFinishedCountLocked(wave);
            if (finished < wave.getDeviceIds().size()) {
                return new WaveAdvanceResult(false, "WAVE_NOT_FINISHED", current, current,
                        status,
                        "wave " + current + " not finished: " + finished + "/"
                                + wave.getDeviceIds().size() + " devices at terminal state");
            }
            // 当前波实际已全部结束但状态尚未收敛（正常路径不会发生），先收敛再判定
            wave.setStatus(WaveStatus.FINISHED);
            onCurrentWaveJustFinishedLocked(wave);
        }

        // wave 已 FINISHED：决定是否还有下一波
        if (current >= waves.size() - 1) {
            return new WaveAdvanceResult(false, "ALREADY_LAST_WAVE", current, -1, status,
                    "wave " + current + " is the last wave of batch '" + batchId + "'");
        }
        int next = current + 1;
        waves.get(next).setStatus(WaveStatus.ACTIVE);
        activeWaveIndex = next;
        return new WaveAdvanceResult(true, "ADVANCED", current, next, status,
                "wave " + current + " FINISHED, wave " + next + " opened");
    }

    /** 推进过程中发现当前波刚刚收敛：若是最后一波则整批 COMPLETED */
    private void onCurrentWaveJustFinishedLocked(Wave wave) {
        if (wave.getIndex() == waves.size() - 1 && !thresholdReachedLocked()) {
            status = BatchStatus.COMPLETED;
        }
    }

    /** 设备是否属于当前放开波次（未来波次/无放开波次均为 false） */
    public boolean isInActiveWave(BatchDeviceEntry entry) {
        synchronized (this) {
            ensureWavesLocked();
            return activeWaveIndex >= 0 && entry.getWaveIndex() == activeWaveIndex;
        }
    }

    /**
     * 批次处于熔断/回滚后时，对“与该设备上次已接受上报一致的重复上报”按幂等放行：
     * 返回与只上报一次完全一致的结果，不改动状态与计数；其余返回 null（调用方拒绝）。
     */
    public synchronized BatchProgressOutcome rollbackDuplicateOrNull(ProgressEvent event) {
        BatchDeviceEntry entry = entries.get(event.deviceId());
        if (entry == null) {
            return null;
        }
        String key = event.eventId() != null && !event.eventId().isBlank()
                ? "id:" + event.eventId()
                : "phase:" + event.phase().name();
        DeviceUpgradeState s = entry.getState();

        // 已升级成功后又被回滚恢复：回滚前那次 SUCCEEDED 的重复上报仍按幂等返回原升级态
        if (s == DeviceUpgradeState.ROLLBACK_SUCCEEDED
                && event.phase() == ProgressPhase.SUCCEEDED) {
            return new BatchProgressOutcome(false, true, DeviceUpgradeState.SUCCEEDED, false,
                    "duplicate SUCCEEDED report after rollback, state had been SUCCEEDED before restore");
        }
        // 回滚时还没开始的设备，任何进度都不接受（心跳由调用方另行处理）
        if (s == DeviceUpgradeState.ROLLBACK_SKIPPED) {
            return null;
        }
        // ROLLBACK_FAILED：回滚前可能是 IN_PROGRESS（有已接受的下载/安装上报）或 FAILED。
        // 只放行与它回滚前最后一次已接受上报一致的重复上报，呈现回滚前的升级态视图。
        String acceptedKey = entry.getLastAcceptedEventKey();
        if (s == DeviceUpgradeState.ROLLBACK_FAILED) {
            if (!key.equals(acceptedKey)) {
                return null;
            }
            DeviceUpgradeState view = entry.getPreRollbackState();
            return new BatchProgressOutcome(false, true,
                    view != null ? view : DeviceUpgradeState.ROLLBACK_FAILED, false,
                    "duplicate report '" + key + "' replayed after rollback marked manual");
        }
        // ROLLBACK_SUCCEEDED 但不是重复 SUCCEEDED（例如不同键）：不放行
        if (s == DeviceUpgradeState.ROLLBACK_SUCCEEDED) {
            return null;
        }
        // 熔断中（CIRCUIT_OPEN）：仍是升级态的设备，只允许与已接受键完全一致的重复上报
        if (key.equals(entry.getLastAcceptedEventKey())) {
            if (event.phase() == ProgressPhase.SUCCEEDED || event.phase() == ProgressPhase.FAILED) {
                return new BatchProgressOutcome(false, true, s, false,
                        "duplicate terminal report '" + key + "' ignored during rollback window");
            }
            if (event.phase() == ProgressPhase.DOWNLOADING || event.phase() == ProgressPhase.INSTALLING) {
                return new BatchProgressOutcome(false, true, s, false,
                        "duplicate progress report '" + key + "' ignored during rollback window");
            }
        }
        return null;
    }

    /** 锁内生成每个波次各自的进度与统计快照 */
    public synchronized List<WaveSnapshot> waveSnapshotsLocked() {
        ensureWavesLocked();
        List<WaveSnapshot> result = new ArrayList<>(waves.size());
        for (Wave wave : waves) {
            int succ = 0, fail = 0, inProgress = 0, notStarted = 0;
            int rolledBack = 0, skipped = 0, rollbackFailed = 0;
            for (String id : wave.getDeviceIds()) {
                switch (entries.get(id).getState()) {
                    case SUCCEEDED -> succ++;
                    case FAILED -> fail++;
                    case IN_PROGRESS -> inProgress++;
                    case NOT_STARTED -> notStarted++;
                    // 回滚终态单独计数：已回滚的设备绝不悄悄算进“未开始”
                    case ROLLBACK_SUCCEEDED -> rolledBack++;
                    case ROLLBACK_SKIPPED -> skipped++;
                    case ROLLBACK_FAILED -> rollbackFailed++;
                }
            }
            result.add(new WaveSnapshot(wave.getIndex(), wave.getStatus(),
                    wave.getDeviceIds().size(), succ, fail, inProgress, notStarted,
                    rolledBack, skipped, rollbackFailed,
                    wave.getDeviceIds()));
        }
        return result;
    }
}
