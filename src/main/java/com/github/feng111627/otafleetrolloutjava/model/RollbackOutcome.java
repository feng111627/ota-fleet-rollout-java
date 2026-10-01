package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 整个批次的回滚结果。
 */
public record RollbackOutcome(String batchId,
                              BatchStatus finalStatus,
                              int rolledBackSucceeded,
                              int skippedNotStarted,
                              int failedDevices,
                              List<RollbackDeviceResult> deviceResults) {
}
