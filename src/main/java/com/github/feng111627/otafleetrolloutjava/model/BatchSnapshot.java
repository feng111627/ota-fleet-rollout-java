package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 批次只读快照（查询与日志用）。
 */
public record BatchSnapshot(String batchId,
                            String firmwareId,
                            BatchStatus status,
                            int totalDevices,
                            int succeededCount,
                            int failedCount,
                            int failureRateThresholdPercent,
                            boolean rollbackDone,
                            List<BatchDeviceEntry> entries) {
}
