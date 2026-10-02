package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 批次只读快照（查询与日志用）。
 *
 * @param currentWaveNo 当前放开的波次号（0 起）；单波批次启动后为 0，启动前 -1
 * @param waves         波次模式下各波统计；单波批次为空列表
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
                            int currentWaveNo,
                            int waveCount,
                            List<WaveSnapshot> waves) {

    /** 兼容旧用法：不带波次信息（currentWaveNo=-1, waveCount=1, waves 空） */
    public BatchSnapshot(String batchId,
                         String firmwareId,
                         BatchStatus status,
                         int totalDevices,
                         int succeededCount,
                         int failedCount,
                         int failureRateThresholdPercent,
                         boolean rollbackDone,
                         List<BatchDeviceEntry> entries) {
        this(batchId, firmwareId, status, totalDevices, succeededCount, failedCount,
                failureRateThresholdPercent, rollbackDone, entries, -1, 1, List.of());
    }
}
