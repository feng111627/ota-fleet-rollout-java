package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.exception.BatchStateException;
import com.github.feng111627.otafleetrolloutjava.exception.CompatibilityException;
import com.github.feng111627.otafleetrolloutjava.compat.RejectReason;
import com.github.feng111627.otafleetrolloutjava.model.Batch;
import com.github.feng111627.otafleetrolloutjava.model.BatchSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ProgressEvent;
import com.github.feng111627.otafleetrolloutjava.model.ProgressPhase;
import com.github.feng111627.otafleetrolloutjava.model.ProgressReportResult;
import com.github.feng111627.otafleetrolloutjava.registry.DeviceRegistry;
import com.github.feng111627.otafleetrolloutjava.registry.FirmwareRegistry;
import com.github.feng111627.otafleetrolloutjava.rollout.RollbackExecutor;
import com.github.feng111627.otafleetrolloutjava.rollout.RolloutOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正常灰度推进：建批 -> 启动 -> 进度上报 -> 全部成功；
 * 以及暂停期间拒绝上报、恢复后继续。
 */
class RolloutHappyPathTest {

    private static final Logger log = LoggerFactory.getLogger(RolloutHappyPathTest.class);

    private RolloutOrchestrator orchestrator;
    private DeviceRegistry devices;

    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        FirmwareRegistry firmwares = new FirmwareRegistry();
        orchestrator = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(), new RollbackExecutor());

        orchestrator.registerFirmware(new FirmwarePackage(
                "fw1", "M1", "2.0.0", "1.0.0", Map.of("k", "v")));
        for (int i = 1; i <= 3; i++) {
            orchestrator.registerDevice(new Device(
                    "d" + i, "M1", "1.2.0", Map.of("k", "v")));
        }
    }

    @Test
    void shouldProgressAllDevicesToCompleted() {
        Batch batch = orchestrator.createBatch("b1", "fw1", List.of("d1", "d2", "d3"), 50);
        orchestrator.start("b1");

        for (String d : List.of("d1", "d2", "d3")) {
            log.info("[happy] report DOWNLOADING device={}", d);
            ProgressReportResult r1 = orchestrator.report(
                    new ProgressEvent("b1", d, ProgressPhase.DOWNLOADING, null, null));
            assertTrue(r1.accepted());
            assertEquals(DeviceUpgradeState.IN_PROGRESS, r1.deviceState());

            log.info("[happy] report INSTALLING device={}", d);
            orchestrator.report(new ProgressEvent("b1", d, ProgressPhase.INSTALLING, null, null));

            log.info("[happy] report SUCCEEDED device={}", d);
            ProgressReportResult r3 = orchestrator.report(
                    new ProgressEvent("b1", d, ProgressPhase.SUCCEEDED, null, null));
            assertEquals(DeviceUpgradeState.SUCCEEDED, r3.deviceState());
        }

        BatchSnapshot snapshot = orchestrator.snapshot("b1");
        log.info("[happy] final snapshot status={} succeeded={} failed={} total={}",
                snapshot.status(), snapshot.succeededCount(), snapshot.failedCount(),
                snapshot.totalDevices());

        assertEquals(BatchStatus.COMPLETED, snapshot.status());
        assertEquals(3, snapshot.succeededCount());
        assertEquals(0, snapshot.failedCount());

        // 升级成功后设备版本已推进到新版本
        for (String d : List.of("d1", "d2", "d3")) {
            assertEquals("2.0.0", devices.get(d).getCurrentVersion(),
                    "device " + d + " version should be advanced");
        }
    }

    @Test
    void shouldRejectReportsWhilePausedAndAcceptAfterResume() {
        orchestrator.createBatch("b2", "fw1", List.of("d1"), 50);
        orchestrator.start("b2");
        orchestrator.pause("b2");
        log.info("[pause] batch paused, attempting report");

        BatchStateException ex = assertThrows(BatchStateException.class,
                () -> orchestrator.report(
                        new ProgressEvent("b2", "d1", ProgressPhase.DOWNLOADING, null, null)));
        log.info("[pause] rejected reasonCode={} message={}", ex.getReasonCode(), ex.getMessage());
        assertEquals("BATCH_PAUSED", ex.getReasonCode());

        // 心跳即使暂停也能收到（设备在线检测不受影响）
        ProgressReportResult hb = orchestrator.report(ProgressEvent.heartbeat("b2", "d1"));
        assertFalse(hb.accepted());
        assertEquals(DeviceUpgradeState.NOT_STARTED, hb.deviceState());

        orchestrator.resume("b2");
        ProgressReportResult ok = orchestrator.report(
                new ProgressEvent("b2", "d1", ProgressPhase.DOWNLOADING, null, null));
        log.info("[resume] report accepted after resume: state={}", ok.deviceState());
        assertTrue(ok.accepted());
        assertEquals(DeviceUpgradeState.IN_PROGRESS, ok.deviceState());
    }

    @Test
    void shouldRejectBatchCreationWhenOneDeviceIncompatible() {
        orchestrator.registerDevice(new Device("dBad", "M2", "1.0.0", Map.of()));
        CompatibilityException ex = assertThrows(CompatibilityException.class,
                () -> orchestrator.createBatch("bBad", "fw1", List.of("dBad"), 50));
        log.info("[create-reject] reasonCode={} message={}", ex.getReasonCode(), ex.getMessage());
        assertEquals(RejectReason.MODEL_MISMATCH, ex.getReason());
    }
}
