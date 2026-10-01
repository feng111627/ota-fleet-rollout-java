package com.github.feng111627.otafleetrolloutjava.model;

/** 批次内单台设备的升级状态机。 */
public class DeviceUpgradeState {
    private final String deviceId;
    private DeviceUpgradeStatus status;
    private int progress;
    private String lastMessage;
    private long updatedAt;
    private boolean pushDispatched;
    private boolean counted; // 终态计数是否已计入批次

    public DeviceUpgradeState(String deviceId) {
        this.deviceId = deviceId;
        this.status = DeviceUpgradeStatus.PENDING;
        this.progress = 0;
        this.lastMessage = "等待推送";
        this.updatedAt = System.currentTimeMillis();
    }

    public String getDeviceId() { return deviceId; }
    public DeviceUpgradeStatus getStatus() { return status; }
    public void setStatus(DeviceUpgradeStatus status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public boolean isPushDispatched() { return pushDispatched; }
    public void setPushDispatched(boolean pushDispatched) { this.pushDispatched = pushDispatched; }
    public boolean isCounted() { return counted; }
    public void setCounted(boolean counted) { this.counted = counted; }
}
