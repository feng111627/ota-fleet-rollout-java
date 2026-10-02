package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 单台设备的回滚处置结果，用于区分“已升级成功被恢复”和“尚未开始无需回滚”。
 */
public record RollbackDeviceResult(String deviceId,
                                   String originalVersion,
                                   DeviceUpgradeState beforeState,
                                   DeviceUpgradeState outcome,
                                   int waveIndex) {

    /** 兼容旧用法（默认波次 0） */
    public RollbackDeviceResult(String deviceId, String originalVersion,
                                DeviceUpgradeState beforeState, DeviceUpgradeState outcome) {
        this(deviceId, originalVersion, beforeState, outcome, 0);
    }
}
