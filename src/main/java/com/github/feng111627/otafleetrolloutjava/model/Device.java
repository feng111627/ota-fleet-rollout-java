package com.github.feng111627.otafleetrolloutjava.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备实体。
 */
public class Device {

    private final String deviceId;
    private final String model;
    private volatile String currentVersion;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    public Device(String deviceId, String model, String currentVersion, Map<String, String> attributes) {
        this.deviceId = deviceId;
        this.model = model;
        this.currentVersion = currentVersion;
        if (attributes != null) {
            this.attributes.putAll(attributes);
        }
    }

    public String getDeviceId() { return deviceId; }
    public String getModel() { return model; }
    public String getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
    public Map<String, String> getAttributes() { return attributes; }
}
