package com.github.feng111627.otafleetrolloutjava.registry;

import com.github.feng111627.otafleetrolloutjava.compat.RejectReason;
import com.github.feng111627.otafleetrolloutjava.exception.CompatibilityException;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 固件包本地登记表：基于内存 ConcurrentHashMap，登记重复时给出可区分的拒绝原因。
 */
public class FirmwareRegistry {

    private final ConcurrentHashMap<String, FirmwarePackage> firmwares = new ConcurrentHashMap<>();

    /**
     * 登记固件包；firmwareId 已存在时拒绝。
     */
    public FirmwarePackage register(FirmwarePackage firmware) {
        FirmwarePackage existing = firmwares.putIfAbsent(firmware.getFirmwareId(), firmware);
        if (existing != null) {
            throw new CompatibilityException(RejectReason.FIRMWARE_ALREADY_REGISTERED,
                    "firmware '" + firmware.getFirmwareId() + "' already registered");
        }
        return firmware;
    }

    public Optional<FirmwarePackage> find(String firmwareId) {
        return Optional.ofNullable(firmwares.get(firmwareId));
    }

    /** 取得固件包，不存在时抛出 FIRMWARE_NOT_FOUND */
    public FirmwarePackage get(String firmwareId) {
        FirmwarePackage firmware = firmwares.get(firmwareId);
        if (firmware == null) {
            throw new CompatibilityException(RejectReason.FIRMWARE_NOT_FOUND,
                    "firmware '" + firmwareId + "' not registered");
        }
        return firmware;
    }

    public List<FirmwarePackage> list() {
        return List.copyOf(firmwares.values());
    }
}
