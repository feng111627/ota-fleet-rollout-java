package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.exception.BatchStateException;
import com.github.feng111627.otafleetrolloutjava.model.Batch;
import com.github.feng111627.otafleetrolloutjava.model.BatchDeviceEntry;
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
import com.github.feng111627.otafleetrolloutjava.model.WaveAdvanceResult;
import com.github.feng111627.otafleetrolloutjava.model.WaveRollbackSummary;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 波次与熔断回滚的衔接、跨波次回滚、回滚中断重试，以及波次维度的暂停/恢复：
 * <ul>
 *   <li>某一波触发熔断回滚后，后面未开始波次原地不动，回滚完成后不允许再推进波次；</li>
 *   <li>回滚按波次分别给出处置结果（已成功恢复 / 未开始跳过 / 状态不明人工）；</li>
 *   <li>回滚中途报错/打断，重试接着处理未处理设备、已处理不重复，无半推半退；</li>
 *   <li>暂停对所有波次生效，恢复后从停下的波次继续。</li>
 * </ul>
 */
class WaveRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(WaveRollbackTest.class);

    private DeviceRegistry devices;
    private FirmwareRegistry firmwares;

    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        firmwares = new FirmwareRegistry();
    }

    private RolloutOrchestrator newSystem(DeviceRestoreOperator restoreOperator) {
        RolloutOrchestrator orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(),
                new RollbackExecutor(restoreOperator));
        orch.registerFirmware(new FirmwarePackage("fw", "M", "2.0.0", "1.0.0", Map.of()));
        // 3 波：wave0=[d0..d2] wave1=[d3..d5] wave2=[d6,d7]
        for (int i = 0; i < 8; i++) {
            orch.registerDevice(new Device("d" + i, "M", "1.0.0", Map.of()));
        }
        return orch;
    }

    private List<List<String>> waves() {
        return List.of(
                List.of("d0", "d1", "d2"),
                List.of("d3", "d4", "d5"),
                List.of("d6", "d7"));
    }

    private List<String> all() {
        return List.of("d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7");
    }

    private ProgressEvent ev(String batch, String device, ProgressPhase phase, String msg, String eid) {
        return new ProgressEvent(batch, device, phase, msg, eid);
    }

    private Map<String, RollbackDeviceResult> byDevice(RollbackOutcome o) {
        Map<String, RollbackDeviceResult> m = new java.util.HashMap<>();
        o.deviceResults().forEach(r -> m.put(r.deviceId(), r));
        return m;
    }

    @Test
    void circuitInSecondWaveFreezesLaterWavesAndRollsBackPerWave() {
        RolloutOrchestrator orch = newSystem(new LocalRestoreOperator());
        // 阈值 20%：第二波在 1 成 1 败时，整批已结束设备为 4 成 1 败（失败率 20%）即熔断。
        // 失败率仍以“整批已结束设备”为分母（上一轮语义），前序成功波会稀释比例。
        orch.createBatch("rb", "fw", all(), 20, waves());
        orch.start("rb");
        // 第一波全部成功并推进到第二波
        for (String d : List.of("d0", "d1", "d2")) {
            orch.report(ev("rb", d, ProgressPhase.SUCCEEDED, null, null));
        }
        assertEquals(WaveAdvanceReason(orch, "rb"), "ADVANCED");
        // 第二波：d3 成功、d4 进行中、d5 失败 -> 已结束 1 成 1 败 = 50% 熔断
        orch.report(ev("rb", "d3", ProgressPhase.SUCCEEDED, null, null));
        orch.report(ev("rb", "d4", ProgressPhase.INSTALLING, null, null));
        ProgressReportResult trip = orch.report(ev("rb", "d5", ProgressPhase.FAILED, "flash fail", null));
        log.info("[rb] d5 failed => circuit={}, status={}", trip.circuitOpened(), trip.batchStatus());
        assertTrue(trip.circuitOpened());
        assertEquals(BatchStatus.ROLLED_BACK, trip.batchStatus());

        // 第三波必须原地不动，且永远不能再推进
        BatchSnapshot snap = orch.snapshot("rb");
        assertEquals(2, snap.waves().get(2).waveIndex());
        assertEquals(com.github.feng111627.otafleetrolloutjava.model.WaveStatus.PENDING,
                snap.waves().get(2).status());
        assertEquals(2, snap.waves().get(2).notStartedCount());
        BatchStateException blocked = assertThrows(BatchStateException.class,
                () -> orch.advanceWave("rb"));
        log.info("[rb] advance after rollback rejected code={}", blocked.getReasonCode());
        assertEquals("BATCH_ROLLED_BACK", blocked.getReasonCode());
        // 第三波设备上报仍被拒
        BatchStateException rep = assertThrows(BatchStateException.class,
                () -> orch.report(ev("rb", "d6", ProgressPhase.SUCCEEDED, null, null)));
        assertEquals("BATCH_ROLLED_BACK", rep.getReasonCode());

        RollbackOutcome o = orch.rollback("rb"); // 幂等复现
        log.info("[rb] rollback totals restore={} skip={} manual={}; perWave={}",
                o.rolledBackSucceeded(), o.skippedNotStarted(), o.failedDevices(), o.waveSummaries());

        // 整批：d0,d1,d2,d3 曾成功（4 台恢复）；d4 IN_PROGRESS、d5 FAILED（2 台人工）；d6,d7 未开始（2 台跳过）
        assertEquals(4, o.rolledBackSucceeded());
        assertEquals(2, o.skippedNotStarted());
        assertEquals(2, o.failedDevices());

        // 按波次分别核对
        List<WaveRollbackSummary> ws = o.waveSummaries();
        assertEquals(3, ws.size());
        assertEquals(new WaveRollbackSummary(0, 3, 3, 0, 0), ws.get(0));
        assertEquals(new WaveRollbackSummary(1, 3, 1, 0, 2), ws.get(1));
        assertEquals(new WaveRollbackSummary(2, 2, 0, 2, 0), ws.get(2));

        Map<String, RollbackDeviceResult> m = byDevice(o);
        assertEquals(DeviceUpgradeState.ROLLBACK_SUCCEEDED, m.get("d0").outcome());
        assertEquals(0, m.get("d0").waveIndex());
        assertEquals(DeviceUpgradeState.ROLLBACK_SUCCEEDED, m.get("d3").outcome());
        assertEquals(1, m.get("d3").waveIndex());
        assertEquals(DeviceUpgradeState.ROLLBACK_FAILED, m.get("d4").outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_FAILED, m.get("d5").outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_SKIPPED, m.get("d6").outcome());
        assertEquals(2, m.get("d6").waveIndex());

        // 曾成功的设备恢复原版本；未开始的保持原版本
        for (String d : List.of("d0", "d1", "d2", "d3")) {
            assertEquals("1.0.0", devices.get(d).getCurrentVersion(), d + " 应恢复原版本");
        }
        assertEquals("1.0.0", devices.get("d6").getCurrentVersion());
    }

    private String WaveAdvanceReason(RolloutOrchestrator orch, String batch) {
        return orch.advanceWave(batch).reasonCode();
    }

    @Test
    void rollbackInterruptedMidwayResumesWithoutDoubleHandling() {
        // 恢复计数器：验证中断重试不会对同一台设备重复恢复
        AtomicInteger restoreCalls = new AtomicInteger();
        DeviceRestoreOperator countingRestore = (device, targetVersion) -> {
            restoreCalls.incrementAndGet();
            device.setCurrentVersion(targetVersion);
            return true;
        };

        // 第一次回滚在处理完 2 台设备后“被打断”；关闭开关后同实例重试不再中断，
        // 贴合真实“首次执行报错、再次尝试恢复正常”的重试路径。
        InterruptibleRollbackExecutor interrupting =
                new InterruptibleRollbackExecutor(countingRestore, 2);

        RolloutOrchestrator orch = new RolloutOrchestrator(devices, firmwares,
                new CompatibilityService(), interrupting);
        orch.registerFirmware(new FirmwarePackage("fw", "M", "2.0.0", "1.0.0", Map.of()));
        for (int i = 0; i < 8; i++) {
            orch.registerDevice(new Device("d" + i, "M", "1.0.0", Map.of()));
        }
        orch.createBatch("rx", "fw", all(), 100, waves());
        orch.start("rx");
        // 第一波 d0,d1 成功，d2 失败也不会熔断（阈值 100），随后人工回滚
        orch.report(ev("rx", "d0", ProgressPhase.SUCCEEDED, null, null));
        orch.report(ev("rx", "d1", ProgressPhase.SUCCEEDED, null, null));
        orch.report(ev("rx", "d2", ProgressPhase.FAILED, "boom", null));

        log.info("[rx] first rollback attempt interrupted after 2 devices");
        assertThrows(IllegalStateException.class, () -> orch.rollback("rx"));
        assertEquals(BatchStatus.CIRCUIT_OPEN, orch.snapshot("rx").status(),
                "中断后批次必须停留在 CIRCUIT_OPEN，不能是半推半退的 ROLLED_BACK");

        // 前 2 台（按快照顺序 d0,d1，曾成功）已恢复；其余未处理
        BatchSnapshot mid = orch.snapshot("rx");
        Map<String, DeviceUpgradeState> midState = new java.util.HashMap<>();
        mid.entries().forEach(e -> midState.put(e.getDeviceId(), e.getState()));
        log.info("[rx] mid states: {}", midState);
        assertEquals(DeviceUpgradeState.ROLLBACK_SUCCEEDED, midState.get("d0"));
        assertEquals(DeviceUpgradeState.ROLLBACK_SUCCEEDED, midState.get("d1"));
        assertEquals(DeviceUpgradeState.FAILED, midState.get("d2"));
        assertEquals(DeviceUpgradeState.NOT_STARTED, midState.get("d6"));

        // 关闭中断开关，用同一执行器重试：接着处理未处理设备，已处理设备跳过
        interrupting.setInterruptEnabled(false);
        RollbackOutcome outcome = orch.rollback("rx");

        log.info("[rx] resumed totals restore={} skip={} manual={}, restoreCalls={}",
                outcome.rolledBackSucceeded(), outcome.skippedNotStarted(),
                outcome.failedDevices(), restoreCalls.get());
        assertEquals(BatchStatus.ROLLED_BACK, orch.snapshot("rx").status());
        // d0,d1 曾成功（恢复），d2 FAILED 人工，d3..d7 未开始跳过
        assertEquals(2, outcome.rolledBackSucceeded());
        assertEquals(5, outcome.skippedNotStarted());
        assertEquals(1, outcome.failedDevices());
        // 关键幂等：恢复 I/O 只对 d0,d1 各执行一次，重试不重复恢复
        assertEquals(2, restoreCalls.get(), "已处理设备不得被重复恢复");

        // 再重试：回滚已完成，直接复现结果，计数仍一致且不触发恢复
        RollbackOutcome again = orch.rollback("rx");
        assertEquals(2, again.rolledBackSucceeded());
        assertEquals(5, again.skippedNotStarted());
        assertEquals(1, again.failedDevices());
        assertEquals(2, restoreCalls.get(), "复现结果不得再触发恢复");
    }

    @Test
    void pauseFreezesAllWavesAndResumeContinuesFromCurrentWave() {
        RolloutOrchestrator orch = newSystem(new LocalRestoreOperator());
        orch.createBatch("ps", "fw", all(), 100, waves());
        orch.start("ps");
        orch.report(ev("ps", "d0", ProgressPhase.SUCCEEDED, null, null));
        orch.pause("ps");

        // 暂停后当前波次的上报被拒
        BatchStateException ex = assertThrows(BatchStateException.class,
                () -> orch.report(ev("ps", "d1", ProgressPhase.SUCCEEDED, null, null)));
        assertEquals("BATCH_PAUSED", ex.getReasonCode());
        // 暂停期间推进也不允许
        BatchStateException adv = assertThrows(BatchStateException.class,
                () -> orch.advanceWave("ps"));
        assertEquals("BATCH_PAUSED", adv.getReasonCode());
        // 心跳仍接受
        assertFalse(orch.report(ProgressEvent.heartbeat("ps", "d1")).accepted());

        orch.resume("ps");
        // 从停下的第一波继续
        orch.report(ev("ps", "d1", ProgressPhase.SUCCEEDED, null, null));
        orch.report(ev("ps", "d2", ProgressPhase.SUCCEEDED, null, null));
        WaveAdvanceResult r = orch.advanceWave("ps");
        assertTrue(r.advanced());
        assertEquals(1, r.activeWaveIndex());

        BatchSnapshot snap = orch.snapshot("ps");
        log.info("[ps] after resume+advance activeWave={} wave0succ={} wave1notStarted={}",
                snap.activeWaveIndex(), snap.waves().get(0).succeededCount(),
                snap.waves().get(1).notStartedCount());
        assertEquals(3, snap.waves().get(0).succeededCount());
        assertEquals(3, snap.waves().get(1).notStartedCount());
    }
}
