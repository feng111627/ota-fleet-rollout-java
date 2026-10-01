package com.github.feng111627.otafleetrolloutjava.service;

import com.github.feng111627.otafleetrolloutjava.api.RegisterDeviceRequest;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.DeviceModel;
import com.github.feng111627.otafleetrolloutjava.model.ModelVersion;
import com.github.feng111627.otafleetrolloutjava.model.RejectReason;
import com.github.feng111627.otafleetrolloutjava.repository.DeviceRepository;
import com.github.feng111627.otafleetrolloutjava.service.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DeviceService {
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository deviceRepository;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    public Device register(RegisterDeviceRequest request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("设备 id 不能为空");
        }
        DeviceModel model = DeviceModel.fromCode(request.model());
        ModelVersion version = ModelVersion.parse(request.currentVersion());
        Set<String> capabilities = request.capabilities() == null
                ? Set.of() : new HashSet<>(request.capabilities());
        Device device = new Device(request.id(), model, version, capabilities);
        deviceRepository.save(device);
        log.info("[设备登记] id={}, model={}, version={}, capabilities={} => 登记成功",
                request.id(), model.getCode(), version, capabilities);
        return device;
    }

    public Device get(String id) {
        return deviceRepository.findById(id).orElseThrow(() -> {
            log.warn("[设备查询] id={} => 不存在", id);
            return new DomainException(RejectReason.DEVICE_NOT_FOUND, "deviceId=" + id);
        });
    }

    public List<Device> list() {
        return deviceRepository.findAll();
    }

    public void heartbeat(String deviceId) {
        Device device = get(deviceId);
        device.setLastHeartbeatAt(System.currentTimeMillis());
        log.info("[设备心跳] deviceId={}, 当前版本={}, 心跳时间={} => 已记录",
                deviceId, device.getCurrentVersion(), device.getLastHeartbeatAt());
    }
}
