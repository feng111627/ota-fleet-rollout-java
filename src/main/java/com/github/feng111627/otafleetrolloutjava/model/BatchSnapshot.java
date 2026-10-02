package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 批次只读快照（查询与日志用），含每个波次各自的进度与统计。
 */
public record BatchSnapshot(String batchId,
                            String firmwareId,
                            BatchStatus status,
                            int totalDevices,
                            int succeededCount,
                            int failedCount,
                            int failureRateThresholdPercent,
                            boolean rollbackDone,
                            List<BatchDeviceEntry> entries,
                            int waveCount,
                            int activeWaveIndex,
                            List<WaveSnapshot> waves) {

    /** 兼容旧用法：按单波次快照构造 */
    public BatchSnapshot(String batchId, String firmwareId, BatchStatus status,
                         int totalDevices, int succeededCount, int failedCount,
                         int failureRateThresholdPercent, boolean rollbackDone,
                         List<BatchDeviceEntry> entries) {
        this(batchId, firmwareId, status, totalDevices, succeededCount, failedCount,
                failureRateThresholdPercent, rollbackDone, entries, 1, 0, List.of());
    }
}
