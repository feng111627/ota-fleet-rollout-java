package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 回滚启动瞬间单台设备的快照（在批次锁内采集，保证无中间态）。
 */
public record RollbackCaptureItem(BatchDeviceEntry entry,
                                  DeviceUpgradeState beforeState,
                                  String originalVersion) {
}
