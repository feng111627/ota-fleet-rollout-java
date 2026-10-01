package com.github.feng111627.otafleetrolloutjava.registry;

import com.github.feng111627.otafleetrolloutjava.compat.RejectReason;
import com.github.feng111627.otafleetrolloutjava.exception.CompatibilityException;
import com.github.feng111627.otafleetrolloutjava.model.Device;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备本地登记表：基于内存 ConcurrentHashMap，登记重复时给出可区分的拒绝原因。
 */
public class DeviceRegistry {

    private final ConcurrentHashMap<String, Device> devices = new ConcurrentHashMap<>();

    /**
     * 登记设备；deviceId 已存在时拒绝。
     */
    public Device register(Device device) {
        Device existing = devices.putIfAbsent(device.getDeviceId(), device);
        if (existing != null) {
            throw new CompatibilityException(RejectReason.DEVICE_ALREADY_REGISTERED,
                    "device '" + device.getDeviceId() + "' already registered");
        }
        return device;
    }

    public Optional<Device> find(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    /** 取得设备，不存在时抛出 DEVICE_NOT_FOUND */
    public Device get(String deviceId) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new CompatibilityException(RejectReason.DEVICE_NOT_FOUND,
                    "device '" + deviceId + "' not registered");
        }
        return device;
    }

    public List<Device> list() {
        return List.copyOf(devices.values());
    }
}
