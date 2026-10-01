package com.github.feng111627.otafleetrolloutjava.model;

import java.util.Set;

/** 已登记固件包及其适用条件。 */
public class FirmwarePackage {
    private final String id;
    private final ModelVersion version;
    private final DeviceModel targetModel;
    private final ModelVersion minApplicableVersion;
    private final Set<String> requiredCapabilities;

    public FirmwarePackage(String id, ModelVersion version, DeviceModel targetModel,
                           ModelVersion minApplicableVersion, Set<String> requiredCapabilities) {
        this.id = id;
        this.version = version;
        this.targetModel = targetModel;
        this.minApplicableVersion = minApplicableVersion;
        this.requiredCapabilities = requiredCapabilities;
    }

    public String getId() { return id; }
    public ModelVersion getVersion() { return version; }
    public DeviceModel getTargetModel() { return targetModel; }
    public ModelVersion getMinApplicableVersion() { return minApplicableVersion; }
    public Set<String> getRequiredCapabilities() { return requiredCapabilities; }
}
