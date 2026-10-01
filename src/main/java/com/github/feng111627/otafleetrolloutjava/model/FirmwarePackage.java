package com.github.feng111627.otafleetrolloutjava.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 固件包实体。
 */
public class FirmwarePackage {

    private final String firmwareId;
    private final String targetModel;
    private final String version;
    private final String minFromVersion;
    private final Map<String, String> requiredAttributes = new LinkedHashMap<>();

    public FirmwarePackage(String firmwareId, String targetModel, String version,
                           String minFromVersion, Map<String, String> requiredAttributes) {
        this.firmwareId = firmwareId;
        this.targetModel = targetModel;
        this.version = version;
        this.minFromVersion = minFromVersion;
        if (requiredAttributes != null) {
            this.requiredAttributes.putAll(requiredAttributes);
        }
    }

    public String getFirmwareId() { return firmwareId; }
    public String getTargetModel() { return targetModel; }
    public String getVersion() { return version; }
    public String getMinFromVersion() { return minFromVersion; }
    public Map<String, String> getRequiredAttributes() { return requiredAttributes; }
}
