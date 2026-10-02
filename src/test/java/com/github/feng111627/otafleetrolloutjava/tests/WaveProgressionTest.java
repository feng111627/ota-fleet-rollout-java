package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.exception.BatchStateException;
import com.github.feng111627.otafleetrolloutjava.model.Batch;
import com.github.feng111627.otafleetrolloutjava.model.BatchSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ProgressEvent;
import com.github.feng111627.otafleetrolloutjava.model.ProgressPhase;
import com.github.feng111627.otafleetrolloutjava.model.WaveProgressOutcome;
import com.github.feng111627.otafleetrolloutjava.model.WaveSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.WaveStatus;
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
 * 波次推进与跳波防护：设备按顺序拆波、启动只放开第一波、逐波收口推进、
 * 未放开波次拒绝上报、不能跳波、最后一波走完整批完成、各波统计独立可查。
 */
class WaveProgressionTest {

    private static final Logger log = LoggerFactory.getLogger(WaveProgressionTest.class);

    private RolloutOrchestrator orch;

    @BeforeEach
    void setUp() {
        DeviceRegistry devices = new DeviceRegistry();
        FirmwareRegistry firmwares = new FirmwareRegistry();
        orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(), new RollbackExecutor());
        orch.registerFirmware(new FirmwarePackage("fw1", "M1", "2.0.0", "1.0.0", Map.of()));
        for (int i = 1; i <= 6; i++) {
            orch.registerDevice(new Device("d" + i, "M1", "1.0.0", Map.of()));
        }
    }

    private void succeed(String batch, String device) {
        orch.report(new ProgressEvent(batch, device, ProgressPhase.SUCCEEDED, null, null));
    }

    @Test
    void devicesAreSplitInOrderAndOnlyFirstWaveOpensOnStart() {
        // 6 台按 [2,3,1] 拆三波，顺序必须保持 d1..d6
        Batch batch = orch.createBatchWithWaveSizes("w1", "fw1",
                List.of("d1", "d2", "d3", "d4", "d5", "d6"), 100, List.of(2, 3, 1));
        orch.start("w1");

        List<WaveSnapshot> waves = orch.waveSnapshots("w1");
        log.info("[waves] after start: waveCount={}, currentWave={}",
                waves.size(), orch.snapshot("w1").currentWaveNo());
        assertEquals(3, waves.size());
        assertEquals(List.of("d1", "d2"), waves.get(0).deviceIds());
        assertEquals(List.of("d3", "d4", "d5"), waves.get(1).deviceIds());
        assertEquals(List.of("d6"), waves.get(2).deviceIds());
        assertEquals(WaveStatus.ACTIVE, waves.get(0).status());
        assertEquals(WaveStatus.PENDING, waves.get(1).status());
        assertEquals(WaveStatus.PENDING, waves.get(2).status());
        assertEquals(0, orch.snapshot("w1").currentWaveNo());

        // 第二波设备在上一波未结束时上报，必须被拒绝（防偷偷放开）
        BatchStateException blocked = assertThrows(BatchStateException.class,
                () -> succeed("w1", "d3"));
        log.info("[waves] early report d3 rejected code={}", blocked.getReasonCode());
        assertEquals("WAVE_NOT_OPEN", blocked.getReasonCode());

        // 第一波全部成功，但未手动推进时第二波仍不放开
        succeed("w1", "d1");
        succeed("w1", "d2");
        assertEquals(WaveStatus.ACTIVE, orch.waveSnapshots("w1").get(0).status());
        BatchStateException stillBlocked = assertThrows(BatchStateException.class,
                () -> succeed("w1", "d3"));
        assertEquals("WAVE_NOT_OPEN", stillBlocked.getReasonCode());

        // 推进：第一波收口、第二波放开
        WaveProgressOutcome adv1 = orch.advanceWave("w1");
        log.info("[waves] advance #1: advanced={} current={} reason={}",
                adv1.advanced(), adv1.currentWaveNo(), adv1.reason());
        assertTrue(adv1.advanced());
        assertEquals(1, adv1.currentWaveNo());
        assertFalse(adv1.completed());
        assertEquals(WaveStatus.FINISHED, orch.waveSnapshots("w1").get(0).status());
        assertEquals(WaveStatus.ACTIVE, orch.waveSnapshots("w1").get(1).status());

        // 第二波成功后，不能直接跳过尚未放开的第三波语义；收口第二波再推进
        for (String d : List.of("d3", "d4", "d5")) {
            succeed("w1", d);
        }
        // 第二波全部成功后推进到第三波（不允许跳波，逐波收口）
        WaveProgressOutcome stay = orch.advanceWave("w1");
        assertTrue(stay.advanced());
        assertEquals(2, stay.currentWaveNo());

        // 前两波设备在波次推进后补报成功：幂等返回，不被 WAVE_NOT_CURRENT 误拒
        ProgressEvent late = new ProgressEvent("w1", "d1", ProgressPhase.SUCCEEDED, null, null);
        var lateResult = orch.report(late);
        assertTrue(lateResult.duplicate(), "上一波设备的重复成功上报必须幂等");
        assertEquals(5, orch.snapshot("w1").succeededCount(), "重复补报不得多算成功数");

        // 已收口波次里终态设备的“异阶段新上报”：终态不可翻转，安全地不生效、不计数、不抛异常
        var noFlip = orch.report(new ProgressEvent(
                "w1", "d2", ProgressPhase.INSTALLING, null, "late-install-d2"));
        assertFalse(noFlip.accepted());
        assertFalse(noFlip.duplicate());
        assertEquals(com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState.SUCCEEDED,
                noFlip.deviceState());

        succeed("w1", "d6");
        WaveProgressOutcome advLast = orch.advanceWave("w1");
        log.info("[waves] last advance: advanced={} completed={} reason={}",
                advLast.advanced(), advLast.completed(), advLast.reason());
        assertTrue(advLast.advanced());
        assertTrue(advLast.completed());

        BatchSnapshot snapshot = orch.snapshot("w1");
        assertEquals(BatchStatus.COMPLETED, snapshot.status());
        assertEquals(6, snapshot.succeededCount());
        // 每波统计独立
        List<WaveSnapshot> finalWaves = orch.waveSnapshots("w1");
        assertEquals(2, finalWaves.get(0).succeeded());
        assertEquals(3, finalWaves.get(1).succeeded());
        assertEquals(1, finalWaves.get(2).succeeded());
        for (WaveSnapshot w : finalWaves) {
            assertEquals(WaveStatus.FINISHED, w.status());
            assertEquals(w.total(), w.succeeded() + w.failed() + w.notStarted() + w.inProgress());
        }
        log.info("[waves] completed. per-wave succeeded={},{},{}",
                finalWaves.get(0).succeeded(), finalWaves.get(1).succeeded(),
                finalWaves.get(2).succeeded());
    }


    @Test
    void advanceWhileWaveUnfinishedMustStay() {
        orch.createBatchWithWaveCount("w2", "fw1",
                List.of("d1", "d2", "d3", "d4"), 50, 2); // 均分为 [2,2]
        orch.start("w2");
        succeed("w2", "d1"); // 只成功 1 台，第一波未收口
        WaveProgressOutcome outcome = orch.advanceWave("w2");
        log.info("[stay] advanced={} reason={}", outcome.advanced(), outcome.reason());
        assertFalse(outcome.advanced());
        assertEquals(0, outcome.currentWaveNo());
        // 第二波仍未放开
        assertEquals(WaveStatus.PENDING, orch.waveSnapshots("w2").get(1).status());
    }

    @Test
    void invalidWaveSplitRejectsWholeBatchPlan() {
        // sizes 总和不等于设备数
        assertThrows(IllegalArgumentException.class, () -> orch.createBatchWithWaveSizes(
                "wBad", "fw1", List.of("d1", "d2"), 50, List.of(1, 2)));
        // 波数大于设备数
        assertThrows(IllegalArgumentException.class, () -> orch.createBatchWithWaveCount(
                "wBad2", "fw1", List.of("d1"), 50, 3));
    }
}
