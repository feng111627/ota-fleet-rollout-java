package com.github.feng111627.otafleetrolloutjava.model;

import java.util.Set;

/** 已登记设备。 */
public class Device {
    private final String id;
    private final DeviceModel model;
    private final ModelVersion currentVersion;
    private final Set<String> capabilities;
    private volatile long lastHeartbeatAt;

    public Device(String id, DeviceModel model, ModelVersion currentVersion, Set<String> capabilities) {
        this.id = id;
        this.model = model;
        this.currentVersion = currentVersion;
        this.capabilities = capabilities;
    }

    public String getId() { return id; }
    public DeviceModel getModel() { return model; }
    public ModelVersion getCurrentVersion() { return currentVersion; }
    public Set<String> getCapabilities() { return capabilities; }
    public long getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(long t) { this.lastHeartbeatAt = t; }
}
