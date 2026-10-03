package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 单个波次的只读进度快照：状态、设备数与各波次各自的统计。
 *
 * <p>统计分两套口径，互不混算：
 * <ul>
 *   <li><b>放量期</b>：succeededCount / failedCount / inProgressCount / notStartedCount，
 *       四者之和等于 totalDevices；</li>
 *   <li><b>回滚后</b>：rolledBackCount（被恢复回原版本）/ skippedCount（本来没开始被跳过）/
 *       needsManualCount（状态不明需人工），三者之和等于 totalDevices，
 *       且与整批回滚结果（RollbackOutcome 的按波次汇总）完全一致——
 *       已回滚掉的设备不会被算进“还没开始”。</li>
 * </ul>
 */
public record WaveSnapshot(int waveIndex,
                           WaveStatus status,
                           int totalDevices,
                           int succeededCount,
                           int failedCount,
                           int inProgressCount,
                           int notStartedCount,
                           int rolledBackCount,
                           int skippedCount,
                           int needsManualCount,
                           List<String> deviceIds) {

    /** 兼容旧用法：无回滚统计（回滚三口径均为 0） */
    public WaveSnapshot(int waveIndex, WaveStatus status, int totalDevices,
                        int succeededCount, int failedCount, int inProgressCount,
                        int notStartedCount, List<String> deviceIds) {
        this(waveIndex, status, totalDevices, succeededCount, failedCount, inProgressCount,
                notStartedCount, 0, 0, 0, deviceIds);
    }
}
