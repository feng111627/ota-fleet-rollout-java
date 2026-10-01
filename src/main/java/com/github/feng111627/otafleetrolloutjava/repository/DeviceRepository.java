package com.github.feng111627.otafleetrolloutjava.repository;

import com.github.feng111627.otafleetrolloutjava.model.Device;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 设备本地存储（内存）。 */
@Repository
public class DeviceRepository {
    private final Map<String, Device> devices = new ConcurrentHashMap<>();

    public Device save(Device device) {
        devices.put(device.getId(), device);
        return device;
    }

    public Optional<Device> findById(String id) {
        return Optional.ofNullable(devices.get(id));
    }

    public List<Device> findAll() {
        return new ArrayList<>(devices.values());
    }
}
