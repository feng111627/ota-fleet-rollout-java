package com.github.feng111627.otafleetrolloutjava.service;

import com.github.feng111627.otafleetrolloutjava.api.RegisterFirmwareRequest;
import com.github.feng111627.otafleetrolloutjava.model.DeviceModel;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ModelVersion;
import com.github.feng111627.otafleetrolloutjava.model.RejectReason;
import com.github.feng111627.otafleetrolloutjava.repository.FirmwareRepository;
import com.github.feng111627.otafleetrolloutjava.service.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class FirmwareService {
    private static final Logger log = LoggerFactory.getLogger(FirmwareService.class);

    private final FirmwareRepository firmwareRepository;

    public FirmwareService(FirmwareRepository firmwareRepository) {
        this.firmwareRepository = firmwareRepository;
    }

    public FirmwarePackage register(RegisterFirmwareRequest request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("固件 id 不能为空");
        }
        DeviceModel targetModel = DeviceModel.fromCode(request.targetModel());
        ModelVersion version = ModelVersion.parse(request.version());
        ModelVersion minVersion = (request.minApplicableVersion() == null
                || request.minApplicableVersion().isBlank())
                ? null : ModelVersion.parse(request.minApplicableVersion());
        Set<String> requiredCapabilities = request.requiredCapabilities() == null
                ? Set.of() : new HashSet<>(request.requiredCapabilities());
        FirmwarePackage firmware = new FirmwarePackage(request.id(), version, targetModel,
                minVersion, requiredCapabilities);
        firmwareRepository.save(firmware);
        log.info("[固件登记] id={}, version={}, targetModel={}, minApplicableVersion={}, requiredCaps={} => 登记成功",
                request.id(), version, targetModel.getCode(),
                minVersion == null ? "无下限" : minVersion, requiredCapabilities);
        return firmware;
    }

    public FirmwarePackage get(String id) {
        return firmwareRepository.findById(id).orElseThrow(() -> {
            log.warn("[固件查询] id={} => 不存在", id);
            return new DomainException(RejectReason.FIRMWARE_NOT_FOUND, "firmwareId=" + id);
        });
    }

    public List<FirmwarePackage> list() {
        return firmwareRepository.findAll();
    }
}
