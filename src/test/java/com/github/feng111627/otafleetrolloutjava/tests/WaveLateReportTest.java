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
 * 波次切换节骨眼、批次回滚之后的迟到/重复上报：
 * 无论上报时间点如何，同一台设备同一结果只生效一次，重复上报拿到与只报一次一致的结果，
 * 各波次成功/失败计数不被多算或少算，终态不翻转。
 */
class WaveLateReportTest {

    private static final Logger log = LoggerFactory.getLogger(WaveLateReportTest.class);

    private RolloutOrchestrator orch;
    private DeviceRegistry devices;

    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        FirmwareRegistry firmwares = new FirmwareRegistry();
        orch = new RolloutOrchestrator(devices, firmwares,
                new CompatibilityService(), new RollbackExecutor());
        orch.registerFirmware(new FirmwarePackage("fw", "M", "2.0.0", "1.0.0", Map.of()));
        for (int i = 0; i < 5; i++) {
            orch.registerDevice(new Device("d" + i, "M", "1.0.0", Map.of()));
        }
    }

    private List<List<String>> waves() {
        return List.of(List.of("d0", "d1"), List.of("d2", "d3"), List.of("d4"));
    }

    private ProgressEvent ev(String d, ProgressPhase phase, String eid) {
        return new ProgressEvent("lr", d, phase, null, eid);
    }

    @Test
    void duplicateSuccessAtWaveBoundaryDoesNotDoubleCount() {
        orch.createBatch("lr", "fw", List.of("d0", "d1", "d2", "d3", "d4"), 100, waves());
        orch.start("lr");
        orch.report(ev("d0", ProgressPhase.SUCCEEDED, "e0"));
        orch.report(ev("d1", ProgressPhase.SUCCEEDED, "e1"));
        orch.advanceWave("lr"); // 切到第二波

        // 第一波设备在切换后补报同一 eventId：必须 duplicate、不计数
        ProgressReportResult late1 = orch.report(ev("d0", ProgressPhase.SUCCEEDED, "e0"));
        ProgressReportResult late1b = orch.report(ev("d0", ProgressPhase.SUCCEEDED, "e0"));
        log.info("[lr] late duplicate on finished wave: accepted={} duplicate={} state={}",
                late1.accepted(), late1.duplicate(), late1.deviceState());
        assertFalse(late1.accepted());
        assertTrue(late1.duplicate());
        assertEquals(DeviceUpgradeState.SUCCEEDED, late1.deviceState());
        assertTrue(late1b.duplicate());

        // 第一波设备想补报一个“失败”，终态不可翻转
        ProgressReportResult flip = orch.report(ev("d0", ProgressPhase.FAILED, null));
        assertFalse(flip.accepted());
        assertEquals(DeviceUpgradeState.SUCCEEDED, flip.deviceState());

        BatchSnapshot snap = orch.snapshot("lr");
        assertEquals(2, snap.waves().get(0).succeededCount(), "第一波成功数不能被迟到补报带偏");
        assertEquals(0, snap.waves().get(0).failedCount());
        assertEquals(0, snap.waves().get(1).succeededCount(), "第二波尚未上报");
        // 整批计数同样精确
        assertEquals(2, snap.succeededCount());
        assertEquals(0, snap.failedCount());
    }

    @Test
    void duplicateReportsAfterRollbackProduceSameResultAsSingleReport() {
        orch.createBatch("lr", "fw", List.of("d0", "d1", "d2", "d3", "d4"), 100, waves());
        orch.start("lr");
        // 第一波 d0 成功（带 eventId）、d1 下载中；随后人工回滚
        orch.report(ev("d0", ProgressPhase.SUCCEEDED, "e0"));
        orch.report(ev("d1", ProgressPhase.DOWNLOADING, null));
        assertEquals("2.0.0", devices.get("d0").getCurrentVersion());
        var outcome = orch.rollback("lr");
        log.info("[lr] rolled back: restore={} skip={} manual={}",
                outcome.rolledBackSucceeded(), outcome.skippedNotStarted(), outcome.failedDevices());
        assertEquals(BatchStatus.ROLLED_BACK, orch.snapshot("lr").status());
        assertEquals("1.0.0", devices.get("d0").getCurrentVersion(), "d0 已恢复原版本");

        // 回滚后 d0 补报与已接受成功完全一致的重复上报（相同 eventId）：
        // 结果必须与它在回滚前只报一次一致——duplicate，不抛错，不多算，版本不再变化
        ProgressReportResult dup = orch.report(ev("d0", ProgressPhase.SUCCEEDED, "e0"));
        ProgressReportResult dup2 = orch.report(ev("d0", ProgressPhase.SUCCEEDED, "e0"));
        log.info("[lr] post-rollback duplicate success: accepted={} duplicate={} state={}",
                dup.accepted(), dup.duplicate(), dup.deviceState());
        assertTrue(dup.duplicate());
        assertFalse(dup.accepted());
        assertEquals(DeviceUpgradeState.SUCCEEDED, dup.deviceState(),
                "重复上报返回设备回滚前的升级态视图，与只报一次一致");
        assertEquals("1.0.0", devices.get("d0").getCurrentVersion(), "重复上报不得再推进版本");
        assertTrue(dup2.duplicate());

        // d1（回滚时 IN_PROGRESS -> ROLLBACK_FAILED 人工）补报重复的 DOWNLOADING：幂等放行
        ProgressReportResult d1dup = orch.report(ev("d1", ProgressPhase.DOWNLOADING, null));
        assertTrue(d1dup.duplicate());
        // d1 想报新的成功：拒绝，不能把人工态翻转成成功
        BatchStateException d1new = assertThrows(BatchStateException.class,
                () -> orch.report(ev("d1", ProgressPhase.SUCCEEDED, "new")));
        assertEquals("BATCH_ROLLED_BACK", d1new.getReasonCode());

        // 未开始波次的设备（d2..d4）在回滚后任何进度上报都拒绝
        for (String d : List.of("d2", "d3", "d4")) {
            BatchStateException ex = assertThrows(BatchStateException.class,
                    () -> orch.report(ev(d, ProgressPhase.SUCCEEDED, null)));
            assertEquals("BATCH_ROLLED_BACK", ex.getReasonCode());
        }
        // 心跳始终接受
        assertFalse(orch.report(ProgressEvent.heartbeat("lr", "d4")).accepted());

        // 计数与最终状态：第一波 1 成功（已回滚）、1 人工；其余未开始
        BatchSnapshot fin = orch.snapshot("lr");
        assertEquals(1, fin.succeededCount(), "批次级成功计数仍记录回滚前的那次成功，不重复");
        assertEquals(0, fin.failedCount());
        Map<String, DeviceUpgradeState> state = new java.util.HashMap<>();
        fin.entries().forEach(e -> state.put(e.getDeviceId(), e.getState()));
        assertEquals(DeviceUpgradeState.ROLLBACK_SUCCEEDED, state.get("d0"));
        assertEquals(DeviceUpgradeState.ROLLBACK_FAILED, state.get("d1"));
        assertEquals(DeviceUpgradeState.ROLLBACK_SKIPPED, state.get("d2"));
        assertEquals(DeviceUpgradeState.ROLLBACK_SKIPPED, state.get("d4"));
    }

    @Test
    void lateReportDuringCircuitOpenWindowIsIdempotentAndBlocksNewProgress() {
        // 构造熔断：阈值 34%，第一波 2 成 1 败 => 33% 不熔断，需要 1 成 1 败即 50%，
        // 这里直接用第二台失败凑 50%：d0 成功、d1 失败 -> 1/2=50% 熔断
        orch.createBatch("lr", "fw", List.of("d0", "d1", "d2", "d3", "d4"), 50, waves());
        orch.start("lr");
        orch.report(ev("d0", ProgressPhase.SUCCEEDED, "e0"));
        ProgressReportResult trip = orch.report(
                new ProgressEvent("lr", "d1", ProgressPhase.FAILED, "fail", null));
        assertTrue(trip.circuitOpened());
        // 自动回滚已把批次带到 ROLLED_BACK；这里验证窗口内重复上报幂等
        ProgressReportResult dup = orch.report(ev("d0", ProgressPhase.SUCCEEDED, "e0"));
        assertTrue(dup.duplicate());
        ProgressReportResult dupFail = orch.report(
                new ProgressEvent("lr", "d1", ProgressPhase.FAILED, "fail", null));
        assertTrue(dupFail.duplicate(), "失败终态的重复（默认 phase 键）也应幂等");
        // 后续波次不能上报
        assertThrows(BatchStateException.class,
                () -> orch.report(ev("d2", ProgressPhase.SUCCEEDED, null)));
    }
}
