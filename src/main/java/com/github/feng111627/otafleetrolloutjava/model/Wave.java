package com.github.feng111627.otafleetrolloutjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个波次：按建批顺序切出的一组设备，及其独立的状态与统计。
 *
 * <p>波次持有 {@link BatchDeviceEntry} 的有序引用（与批次内同一份对象），
 * 统计通过扫描条目实时得出，避免与批次计数出现两套真值。
 * 所有状态迁移在批次锁内由 {@link Batch} 驱动完成。
 */
public class Wave {

    private final int waveNo;
    private final List<BatchDeviceEntry> entries;
    private volatile WaveStatus status = WaveStatus.PENDING;

    public Wave(int waveNo, List<BatchDeviceEntry> entries) {
        this.waveNo = waveNo;
        this.entries = List.copyOf(entries);
    }

    public int getWaveNo() { return waveNo; }
    public WaveStatus getStatus() { return status; }
    public void setStatus(WaveStatus status) { this.status = status; }
    public List<BatchDeviceEntry> getEntries() { return entries; }
    public int size() { return entries.size(); }

    /** 本波设备是否全部拿到终态（SUCCEEDED/FAILED）。空波永远不算收口。 */
    public boolean allDevicesFinished() {
        if (entries.isEmpty()) {
            return false;
        }
        for (BatchDeviceEntry entry : entries) {
            DeviceUpgradeState s = entry.getState();
            if (s != DeviceUpgradeState.SUCCEEDED && s != DeviceUpgradeState.FAILED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 生成本波只读统计快照：升级阶段统计 NOT_STARTED/IN_PROGRESS/SUCCEEDED/FAILED，
     * 回滚后额外统计 ROLLBACK_SUCCEEDED/SKIPPED/FAILED。
     */
    public WaveSnapshot snapshot() {
        int succeeded = 0;
        int failed = 0;
        int notStarted = 0;
        int inProgress = 0;
        int rolledBack = 0;
        int rollbackSkipped = 0;
        int rollbackFailed = 0;
        List<String> ids = new ArrayList<>(entries.size());
        for (BatchDeviceEntry entry : entries) {
            ids.add(entry.getDeviceId());
            switch (entry.getState()) {
                case SUCCEEDED -> succeeded++;
                case FAILED -> failed++;
                case NOT_STARTED -> notStarted++;
                case IN_PROGRESS -> inProgress++;
                case ROLLBACK_SUCCEEDED -> rolledBack++;
                case ROLLBACK_SKIPPED -> rollbackSkipped++;
                case ROLLBACK_FAILED -> rollbackFailed++;
            }
        }
        return new WaveSnapshot(waveNo, status, List.copyOf(ids), entries.size(),
                succeeded, failed, notStarted, inProgress,
                rolledBack, rollbackSkipped, rollbackFailed);
    }
}
