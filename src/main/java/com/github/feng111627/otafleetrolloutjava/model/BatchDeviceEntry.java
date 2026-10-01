package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 批次内单台设备的升级记录。
 * 状态迁移由 {@link Batch} 在锁内完成。
 */
public class BatchDeviceEntry {

    private final String deviceId;
    /** 建批时快照的设备原版本，回滚时恢复到该版本 */
    private final String originalVersion;
    private volatile DeviceUpgradeState state = DeviceUpgradeState.NOT_STARTED;
    /** 最近一次已接受的终态上报去重键 */
    private volatile String lastAcceptedEventKey;
    private volatile String failureMessage;

    public BatchDeviceEntry(String deviceId, String originalVersion) {
        this.deviceId = deviceId;
        this.originalVersion = originalVersion;
    }

    public String getDeviceId() { return deviceId; }
    public String getOriginalVersion() { return originalVersion; }
    public DeviceUpgradeState getState() { return state; }
    public void setState(DeviceUpgradeState state) { this.state = state; }
    public String getLastAcceptedEventKey() { return lastAcceptedEventKey; }
    public void setLastAcceptedEventKey(String lastAcceptedEventKey) { this.lastAcceptedEventKey = lastAcceptedEventKey; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
}
