package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 单个波次的只读进度快照：状态、设备数与各波次各自的成功/失败/进行中统计。
 */
public record WaveSnapshot(int waveIndex,
                           WaveStatus status,
                           int totalDevices,
                           int succeededCount,
                           int failedCount,
                           int inProgressCount,
                           int notStartedCount,
                           List<String> deviceIds) {
}
