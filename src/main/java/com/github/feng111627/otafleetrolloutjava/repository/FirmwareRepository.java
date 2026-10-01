package com.github.feng111627.otafleetrolloutjava.repository;

import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 固件包本地存储（内存）。 */
@Repository
public class FirmwareRepository {
    private final Map<String, FirmwarePackage> packages = new ConcurrentHashMap<>();

    public FirmwarePackage save(FirmwarePackage firmwarePackage) {
        packages.put(firmwarePackage.getId(), firmwarePackage);
        return firmwarePackage;
    }

    public Optional<FirmwarePackage> findById(String id) {
        return Optional.ofNullable(packages.get(id));
    }

    public List<FirmwarePackage> findAll() {
        return new ArrayList<>(packages.values());
    }
}
