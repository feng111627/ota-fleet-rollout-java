package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 单个波次维度的回滚处置统计（按波次分别给出处置结果）。
 */
public record WaveRollbackSummary(int waveIndex,
                                  int totalDevices,
                                  int rolledBackSucceeded,
                                  int skippedNotStarted,
                                  int failedDevices) {
}
