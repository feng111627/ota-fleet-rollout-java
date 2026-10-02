package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 整个批次的回滚结果。
 *
 * @param waveResults 波次模式下按波次分别给出的处置结果；单波批次为空列表
 */
public record RollbackOutcome(String batchId,
                              BatchStatus finalStatus,
                              int rolledBackSucceeded,
                              int skippedNotStarted,
                              int failedDevices,
                              List<RollbackDeviceResult> deviceResults,
                              List<WaveRollbackOutcome> waveResults) {

    /** 兼容旧调用：不带波次分组（视为空） */
    public RollbackOutcome(String batchId,
                           BatchStatus finalStatus,
                           int rolledBackSucceeded,
                           int skippedNotStarted,
                           int failedDevices,
                           List<RollbackDeviceResult> deviceResults) {
        this(batchId, finalStatus, rolledBackSucceeded, skippedNotStarted,
                failedDevices, deviceResults, List.of());
    }

    /** 附加波次分组，返回一份新的结果对象 */
    public RollbackOutcome withWaveResults(List<WaveRollbackOutcome> waves) {
        return new RollbackOutcome(batchId, finalStatus, rolledBackSucceeded, skippedNotStarted,
                failedDevices, deviceResults, List.copyOf(waves));
    }
}
