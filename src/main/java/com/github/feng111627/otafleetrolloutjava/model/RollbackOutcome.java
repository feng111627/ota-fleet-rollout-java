package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 整个批次的回滚结果（含按波次分别汇总的处置统计）。
 */
public record RollbackOutcome(String batchId,
                              BatchStatus finalStatus,
                              int rolledBackSucceeded,
                              int skippedNotStarted,
                              int failedDevices,
                              List<RollbackDeviceResult> deviceResults,
                              List<WaveRollbackSummary> waveSummaries) {

    /** 兼容旧用法：不携带波次汇总 */
    public RollbackOutcome(String batchId, BatchStatus finalStatus,
                           int rolledBackSucceeded, int skippedNotStarted, int failedDevices,
                           List<RollbackDeviceResult> deviceResults) {
        this(batchId, finalStatus, rolledBackSucceeded, skippedNotStarted, failedDevices,
                deviceResults, List.of());
    }
}
