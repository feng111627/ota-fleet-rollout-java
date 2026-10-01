package com.github.feng111627.otafleetrolloutjava.controller;

import com.github.feng111627.otafleetrolloutjava.api.*;
import com.github.feng111627.otafleetrolloutjava.model.CompatibilityResult;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.RolloutBatch;
import com.github.feng111627.otafleetrolloutjava.model.RolloutCreateResult;
import com.github.feng111627.otafleetrolloutjava.service.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.service.DeviceService;
import com.github.feng111627.otafleetrolloutjava.service.FirmwareService;
import com.github.feng111627.otafleetrolloutjava.service.RolloutService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AdminController {
    private final DeviceService deviceService;
    private final FirmwareService firmwareService;
    private final RolloutService rolloutService;

    public AdminController(DeviceService deviceService, FirmwareService firmwareService,
                           RolloutService rolloutService) {
        this.deviceService = deviceService;
        this.firmwareService = firmwareService;
        this.rolloutService = rolloutService;
    }

    @PostMapping("/devices")
    public Device registerDevice(@RequestBody RegisterDeviceRequest request) {
        return deviceService.register(request);
    }

    @GetMapping("/devices")
    public List<Device> listDevices() {
        return deviceService.list();
    }

    @PostMapping("/firmwares")
    public FirmwarePackage registerFirmware(@RequestBody RegisterFirmwareRequest request) {
        return firmwareService.register(request);
    }

    @GetMapping("/firmwares")
    public List<FirmwarePackage> listFirmwares() {
        return firmwareService.list();
    }

    @PostMapping("/batches")
    public RolloutCreateResult createBatch(@RequestBody CreateBatchRequest request) {
        return rolloutService.createBatch(request);
    }

    @GetMapping("/batches")
    public List<RolloutBatch> listBatches() {
        return rolloutService.list();
    }

    @GetMapping("/batches/{id}")
    public RolloutBatch getBatch(@PathVariable String id) {
        return rolloutService.get(id);
    }

    @PostMapping("/batches/{id}/advance")
    public RolloutBatch advance(@PathVariable String id) {
        return rolloutService.advance(id);
    }

    @PostMapping("/batches/{id}/pause")
    public RolloutBatch pause(@PathVariable String id) {
        return rolloutService.pause(id);
    }

    @PostMapping("/batches/{id}/resume")
    public RolloutBatch resume(@PathVariable String id) {
        return rolloutService.resume(id);
    }

    @PostMapping("/batches/{id}/rollback")
    public RolloutBatch rollback(@PathVariable String id) {
        return rolloutService.rollback(id);
    }

    @PostMapping("/batches/{id}/reports")
    public RolloutBatch report(@PathVariable String id, @RequestBody ReportRequest request) {
        return rolloutService.report(id, request);
    }

    @PostMapping("/devices/heartbeat")
    public String heartbeat(@RequestBody HeartbeatRequest request) {
        deviceService.heartbeat(request.deviceId());
        return "ok";
    }

    @GetMapping("/compatibility")
    public CompatibilityResponse checkCompatibility(@RequestParam String firmwareId,
                                                    @RequestParam String deviceId) {
        CompatibilityResult result = rolloutService.checkCompatibility(firmwareId, deviceId);
        return new CompatibilityResponse(result.compatible(),
                result.reason() == null ? null : result.reason().name(), result.detail());
    }
}
