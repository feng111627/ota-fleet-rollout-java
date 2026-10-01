package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityResult;
import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.compat.RejectReason;
import com.github.feng111627.otafleetrolloutjava.exception.CompatibilityException;
import com.github.feng111627.otafleetrolloutjava.model.BatchSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ProgressEvent;
import com.github.feng111627.otafleetrolloutjava.model.ProgressPhase;
import com.github.feng111627.otafleetrolloutjava.registry.DeviceRegistry;
import com.github.feng111627.otafleetrolloutjava.registry.FirmwareRegistry;
import com.github.feng111627.otafleetrolloutjava.rollout.LocalRestoreOperator;
import com.github.feng111627.otafleetrolloutjava.rollout.RollbackExecutor;
import com.github.feng111627.otafleetrolloutjava.rollout.RolloutOrchestrator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：登记设备/固件 -> 不同兼容性原因分别拒绝 -> 正常批次完整发布。
 */
class EndToEndIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndIntegrationTest.class);

    @Test
    void registerRejectAndRolloutEndToEnd() {
        DeviceRegistry devices = new DeviceRegistry();
        FirmwareRegistry firmwares = new FirmwareRegistry();
        RolloutOrchestrator orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(),
                new RollbackExecutor(new LocalRestoreOperator()));

        orch.registerFirmware(new FirmwarePackage(
                "fw-2.0", "BOX-A", "2.0.0", "1.5.0", Map.of("secure_boot", "on")));

        Device good = new Device("dev-good", "BOX-A", "1.6.0", Map.of("secure_boot", "on"));
        Device wrongModel = new Device("dev-model", "BOX-B", "1.6.0", Map.of("secure_boot", "on"));
        Device lowVersion = new Device("dev-low", "BOX-A", "1.0.0", Map.of("secure_boot", "on"));
        Device badDep = new Device("dev-dep", "BOX-A", "1.6.0", Map.of("secure_boot", "off"));
        orch.registerDevice(good);
        orch.registerDevice(wrongModel);
        orch.registerDevice(lowVersion);
        orch.registerDevice(badDep);

        assertReject(orch, "dev-model", RejectReason.MODEL_MISMATCH);
        assertReject(orch, "dev-low", RejectReason.CURRENT_VERSION_TOO_LOW);
        assertReject(orch, "dev-dep", RejectReason.DEPENDENCY_NOT_SATISFIED);

        // 未登记设备
        CompatibilityException notFound = assertThrows(CompatibilityException.class,
                () -> orch.checkCompatibility("ghost", "fw-2.0"));
        log.info("[e2e] ghost device reason={}", notFound.getReason());
        assertEquals(RejectReason.DEVICE_NOT_FOUND, notFound.getReason());

        CompatibilityResult ok = orch.checkCompatibility("dev-good", "fw-2.0");
        assertTrue(ok.compatible());

        // 只有兼容设备进入批次并完成发布
        orch.createBatch("e2e-batch", "fw-2.0", List.of("dev-good"), 50);
        orch.start("e2e-batch");
        orch.report(new ProgressEvent("e2e-batch", "dev-good", ProgressPhase.INSTALLING, null, null));
        orch.report(new ProgressEvent("e2e-batch", "dev-good", ProgressPhase.SUCCEEDED, null, null));

        BatchSnapshot snapshot = orch.snapshot("e2e-batch");
        log.info("[e2e] final status={} succeeded={} version={}",
                snapshot.status(), snapshot.succeededCount(),
                devices.get("dev-good").getCurrentVersion());
        assertEquals(BatchStatus.COMPLETED, snapshot.status());
        assertEquals("2.0.0", devices.get("dev-good").getCurrentVersion());
    }

    private void assertReject(RolloutOrchestrator orch, String deviceId, RejectReason expected) {
        CompatibilityResult r = orch.checkCompatibility(deviceId, "fw-2.0");
        log.info("[e2e] device={} compatible={} reason={} detail={}",
                deviceId, r.compatible(), r.reason(), r.detail());
        assertEquals(expected, r.reason());
    }
}
