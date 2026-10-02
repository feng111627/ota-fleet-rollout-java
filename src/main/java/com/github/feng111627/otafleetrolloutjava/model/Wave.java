package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 批次内的一个放量波次：按建批顺序容纳一组设备，同一时刻只有一个波次处于 ACTIVE。
 * 波次状态迁移全部在 {@link Batch} 锁内完成。
 */
public class Wave {

    private final int index;
    private final List<String> deviceIds;
    private volatile WaveStatus status = WaveStatus.PENDING;

    public Wave(int index, List<String> deviceIds) {
        this.index = index;
        this.deviceIds = List.copyOf(deviceIds);
    }

    public int getIndex() { return index; }
    public List<String> getDeviceIds() { return deviceIds; }
    public WaveStatus getStatus() { return status; }
    public void setStatus(WaveStatus status) { this.status = status; }
}
