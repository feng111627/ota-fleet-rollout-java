package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.exception.BatchStateException;
import com.github.feng111627.otafleetrolloutjava.model.BatchSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ProgressEvent;
import com.github.feng111627.otafleetrolloutjava.model.ProgressPhase;
import com.github.feng111627.otafleetrolloutjava.model.ProgressReportResult;
import com.github.feng111627.otafleetrolloutjava.model.RollbackDeviceResult;
import com.github.feng111627.otafleetrolloutjava.model.RollbackOutcome;
import com.github.feng111627.otafleetrolloutjava.registry.DeviceRegistry;
import com.github.feng111627.otafleetrolloutjava.registry.FirmwareRegistry;
import com.github.feng111627.otafleetrolloutjava.rollout.DeviceRestoreOperator;
import com.github.feng111627.otafleetrolloutjava.rollout.LocalRestoreOperator;
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
 * 熔断与回滚测试：
 * 失败率超阈自动熔断回滚；回滚区分已升级成功 / 尚未开始 / 中途或失败设备；
 * 恢复报错时不留假成功；回滚可幂等重入；熔断后上报被拒。
 */
class CircuitBreakerRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRollbackTest.class);

    private DeviceRegistry devices;
    private FirmwareRegistry firmwares;

    /**
     * 设备布局：
     * d1 -> SUCCEEDED（版本已升到 2.0.0，回滚必须恢复 1.0.0）
     * d2 -> NOT_STARTED（回滚应跳过）
     * d3 -> DOWNLOADING/IN_PROGRESS（中途中断，回滚无法远程确认，标记 ROLLBACK_FAILED）
     * d4 -> FAILED（回滚标记 ROLLBACK_FAILED）
     * 阈值 50%：d4 失败时终态为 d1 成功、d4 失败，失败率 50%，立即熔断。
     */
    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        firmwares = new FirmwareRegistry();
        // 构造在每个测试中完成（回滚执行器可替换）
    }

    private RolloutOrchestrator newSystem(DeviceRestoreOperator restoreOperator) {
        RolloutOrchestrator orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(),
                new RollbackExecutor(restoreOperator));
        orch.registerFirmware(new FirmwarePackage("fw1", "M1", "2.0.0", "1.0.0", Map.of()));
        for (String id : List.of("d1", "d2", "d3", "d4")) {
            orch.registerDevice(new Device(id, "M1", "1.0.0", Map.of()));
        }
        return orch;
    }

    private ProgressEvent ev(String batch, String device, ProgressPhase phase, String msg, String eventId) {
        return new ProgressEvent(batch, device, phase, msg, eventId);
    }

    @Test
    void shouldTripCircuitAtThresholdAndRollbackByDeviceCategory() {
        RolloutOrchestrator orch = newSystem(new LocalRestoreOperator()); // 真实本地恢复：写回原版本
        orch.createBatch("cb", "fw1", List.of("d1", "d2", "d3", "d4"), 50);
        orch.start("cb");

        orch.report(ev("cb", "d1", ProgressPhase.SUCCEEDED, null, null));
        orch.report(ev("cb", "d3", ProgressPhase.DOWNLOADING, null, null)); // 中途
        assertEquals("2.0.0", devices.get("d1").getCurrentVersion());

        log.info("[cb] d4 reports FAILED: expected failure rate 1/2 = 50% => circuit open + auto rollback");
        ProgressReportResult failing = orch.report(ev("cb", "d4", ProgressPhase.FAILED, "flash error", null));
        log.info("[cb] report result accepted={} circuitOpened={} batchStatus={}",
                failing.accepted(), failing.circuitOpened(), failing.batchStatus());
        assertTrue(failing.accepted());
        assertTrue(failing.circuitOpened());
        assertEquals(BatchStatus.ROLLED_BACK, failing.batchStatus());

        BatchSnapshot snapshot = orch.snapshot("cb");
        log.info("[cb] snapshot status={} succeeded={} failed={} rollbackDone={}",
                snapshot.status(), snapshot.succeededCount(), snapshot.failedCount(),
                snapshot.rollbackDone());
        assertEquals(BatchStatus.ROLLED_BACK, snapshot.status());
        assertTrue(snapshot.rollbackDone());

        // 熔断后新的进度上报必须被拒绝，原因可区分
        BatchStateException blocked = assertThrows(BatchStateException.class,
                () -> orch.report(ev("cb", "d2", ProgressPhase.DOWNLOADING, null, null)));
        log.info("[cb] post-circuit report rejected code={}", blocked.getReasonCode());
        assertEquals("BATCH_ROLLED_BACK", blocked.getReasonCode());

        // 回滚结果必须区分三类设备
        RollbackOutcome outcome = orch.rollback("cb"); // 幂等重入
        log.info("[cb] rollback outcome rolledBackSucceeded={} skippedNotStarted={} failedDevices={}",
                outcome.rolledBackSucceeded(), outcome.skippedNotStarted(), outcome.failedDevices());
        assertEquals(1, outcome.rolledBackSucceeded());
        assertEquals(1, outcome.skippedNotStarted()); // d2 NOT_STARTED
        assertEquals(2, outcome.failedDevices());     // d3 IN_PROGRESS + d4 FAILED

        Map<String, RollbackDeviceResult> byDevice = new java.util.HashMap<>();
        outcome.deviceResults().forEach(r -> byDevice.put(r.deviceId(), r));
        assertEquals(DeviceUpgradeState.ROLLBACK_SUCCEEDED, byDevice.get("d1").outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_SKIPPED, byDevice.get("d2").outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_FAILED, byDevice.get("d3").outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_FAILED, byDevice.get("d4").outcome());

        // 已升级成功的设备版本被恢复；未开始的保持原版本
        assertEquals("1.0.0", devices.get("d1").getCurrentVersion(), "d1 必须恢复到原版本");
        assertEquals("1.0.0", devices.get("d2").getCurrentVersion());
    }

    @Test
    void failedRestoreMustNotLeaveAFakeRolledBackState() {
        // 模拟 d1 远程恢复超时/报错
        DeviceRestoreOperator flaky = (device, targetVersion) -> {
            // d1 模拟远程恢复超时/报错；其余设备真实写回原版本
            if (device.getDeviceId().equals("d1")) {
                return false;
            }
            device.setCurrentVersion(targetVersion);
            return true;
        };
        RolloutOrchestrator orch = newSystem(flaky);
        orch.createBatch("cb2", "fw1", List.of("d1", "d2", "d3"), 50);
        orch.start("cb2");
        orch.report(ev("cb2", "d1", ProgressPhase.SUCCEEDED, null, null));

        log.info("[cb2] d3 fails => circuit + rollback, restore of d1 simulated to fail");
        ProgressReportResult r = orch.report(ev("cb2", "d3", ProgressPhase.FAILED, "timeout", null));
        assertTrue(r.circuitOpened());

        RollbackOutcome outcome = orch.rollback("cb2"); // 重入复现
        RollbackDeviceResult d1 = outcome.deviceResults().stream()
                .filter(x -> x.deviceId().equals("d1")).findFirst().orElseThrow();
        log.info("[cb2] d1 rollback outcome={} (expect ROLLBACK_FAILED)", d1.outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_FAILED, d1.outcome());
        assertEquals(0, outcome.rolledBackSucceeded());
        // d1 恢复失败 + d3 升级失败（无法远程回滚）都计入 failedDevices
        assertEquals(2, outcome.failedDevices());
        // d2 未开始仍被跳过
        assertEquals(1, outcome.skippedNotStarted());

        // 批次仍标记回滚完成（流程走完），但 d1 状态明确暴露为需人工介入
        assertEquals(BatchStatus.ROLLED_BACK, orch.snapshot("cb2").status());
        // d1 版本没有被写成原版本（恢复失败时不能假装恢复）
        assertEquals("2.0.0", devices.get("d1").getCurrentVersion());
    }

    @Test
    void manualRollbackBeforeAnyProgressSkipsEveryDevice() {
        RolloutOrchestrator orch = newSystem(new LocalRestoreOperator());
        orch.createBatch("cb3", "fw1", List.of("d1", "d2"), 50);
        orch.start("cb3");
        // 无任何进度，直接人工回滚
        RollbackOutcome outcome = orch.rollback("cb3");
        log.info("[cb3] manual rollback with no progress: skipped={} rolledBack={} failed={}",
                outcome.skippedNotStarted(), outcome.rolledBackSucceeded(), outcome.failedDevices());
        assertEquals(2, outcome.skippedNotStarted());
        assertEquals(0, outcome.rolledBackSucceeded());
        assertEquals(0, outcome.failedDevices());
        assertEquals(BatchStatus.ROLLED_BACK, outcome.finalStatus());
    }

    @Test
    void thresholdMustNotTripOnSuccessOnly() {
        RolloutOrchestrator orch = newSystem(new LocalRestoreOperator());
        orch.createBatch("cb4", "fw1", List.of("d1", "d2"), 50);
        orch.start("cb4");
        ProgressReportResult r1 = orch.report(ev("cb4", "d1", ProgressPhase.SUCCEEDED, null, null));
        assertFalse(r1.circuitOpened());
        ProgressReportResult r2 = orch.report(ev("cb4", "d2", ProgressPhase.SUCCEEDED, null, null));
        assertFalse(r2.circuitOpened());
        assertEquals(BatchStatus.COMPLETED, orch.snapshot("cb4").status());
        log.info("[cb4] all success, no circuit: status={}", orch.snapshot("cb4").status());
    }
}
