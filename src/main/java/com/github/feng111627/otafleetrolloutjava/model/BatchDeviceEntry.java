package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 批次内单台设备的升级记录。
 * 状态迁移由 {@link Batch} 在锁内完成。
 */
public class BatchDeviceEntry {

    private final String deviceId;
    /** 建批时快照的设备原版本，回滚时恢复到该版本 */
    private final String originalVersion;
    /** 设备所属波次下标（0 起），未启用波次时为 0 */
    private final int waveIndex;
    private volatile DeviceUpgradeState state = DeviceUpgradeState.NOT_STARTED;
    /** 回滚前的升级状态（回滚提交时记录），用于回滚后重复上报呈现“只报一次”的一致视图 */
    private volatile DeviceUpgradeState preRollbackState;
    /** 最近一次已接受的终态上报去重键 */
    private volatile String lastAcceptedEventKey;
    private volatile String failureMessage;

    public BatchDeviceEntry(String deviceId, String originalVersion) {
        this(deviceId, originalVersion, 0);
    }

    public BatchDeviceEntry(String deviceId, String originalVersion, int waveIndex) {
        this.deviceId = deviceId;
        this.originalVersion = originalVersion;
        this.waveIndex = waveIndex;
    }

    public String getDeviceId() { return deviceId; }
    public String getOriginalVersion() { return originalVersion; }
    public int getWaveIndex() { return waveIndex; }
    public DeviceUpgradeState getState() { return state; }
    public void setState(DeviceUpgradeState state) { this.state = state; }
    public DeviceUpgradeState getPreRollbackState() { return preRollbackState; }
    public void setPreRollbackState(DeviceUpgradeState preRollbackState) {
        this.preRollbackState = preRollbackState;
    }
    public String getLastAcceptedEventKey() { return lastAcceptedEventKey; }
    public void setLastAcceptedEventKey(String lastAcceptedEventKey) { this.lastAcceptedEventKey = lastAcceptedEventKey; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
}
