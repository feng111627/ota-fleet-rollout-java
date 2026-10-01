package com.github.feng111627.otafleetrolloutjava.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一个固件灰度发布批次。 */
public class RolloutBatch {
    private final String id;
    private final String firmwareId;
    private final List<String> orderedDeviceIds;
    private final List<RolloutStage> stages;
    private final double failureRateThreshold;
    private final int minSamplesForCircuit;
    private final long createdAt;
    @JsonIgnore
    private final Object lock = new Object();

    private BatchStatus status;
    private int currentStageIndex = -1;
    private int successCount;
    private int failedCount;
    private int timeoutCount;
    private final Map<String, DeviceUpgradeState> devices = new LinkedHashMap<>();

    public RolloutBatch(String id, String firmwareId, List<String> orderedDeviceIds,
                        List<RolloutStage> stages, double failureRateThreshold,
                        int minSamplesForCircuit, long createdAt) {
        this.id = id;
        this.firmwareId = firmwareId;
        this.orderedDeviceIds = new ArrayList<>(orderedDeviceIds);
        this.stages = stages;
        this.failureRateThreshold = failureRateThreshold;
        this.minSamplesForCircuit = minSamplesForCircuit;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getFirmwareId() { return firmwareId; }
    public List<String> getOrderedDeviceIds() { return orderedDeviceIds; }
    public List<RolloutStage> getStages() { return stages; }
    public double getFailureRateThreshold() { return failureRateThreshold; }
    public int getMinSamplesForCircuit() { return minSamplesForCircuit; }
    public long getCreatedAt() { return createdAt; }
    public Object getLock() { return lock; }
    public BatchStatus getStatus() { return status; }
    public void setStatus(BatchStatus status) { this.status = status; }
    public int getCurrentStageIndex() { return currentStageIndex; }
    public void setCurrentStageIndex(int i) { this.currentStageIndex = i; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int v) { this.successCount = v; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int v) { this.failedCount = v; }
    public int getTimeoutCount() { return timeoutCount; }
    public void setTimeoutCount(int v) { this.timeoutCount = v; }
    public Map<String, DeviceUpgradeState> getDevices() { return devices; }
}
