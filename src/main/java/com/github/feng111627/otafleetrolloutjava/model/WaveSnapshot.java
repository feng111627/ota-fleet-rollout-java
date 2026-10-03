package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 单个波次的只读进度快照：状态、设备数与各波次各自的成功/失败/进行中统计。
 *
 * <p>回滚完成后，波内设备全部处于 ROLLBACK_* 状态，此时
 * succeeded/failed/inProgress/notStarted 均为 0，处置结果看
 * {@code rolledBackSucceededCount / rollbackSkippedCount / rollbackFailedCount}
 * （与 {@link WaveRollbackSummary} 同口径，三者之和等于 totalDevices）；
 * 绝不会把已回滚的设备悄悄算进“未开始”。
 */
public record WaveSnapshot(int waveIndex,
                           WaveStatus status,
                           int totalDevices,
                           int succeededCount,
                           int failedCount,
                           int inProgressCount,
                           int notStartedCount,
                           int rolledBackSucceededCount,
                           int rollbackSkippedCount,
                           int rollbackFailedCount,
                           List<String> deviceIds) {

    /** 回滚处置计数之和（已恢复 + 未开始跳过 + 需人工），回滚完成后等于 totalDevices */
    public int rollbackAccountedCount() {
        return rolledBackSucceededCount + rollbackSkippedCount + rollbackFailedCount;
    }
}
