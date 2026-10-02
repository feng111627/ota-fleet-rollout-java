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
import com.github.feng111627.otafleetrolloutjava.model.WaveAdvanceResult;
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
 * 波次拆分与顺序推进：start 只放开第一波；未放开波次上报被拒；
 * 当前波未结束不能推进（不能跳波）；一波结束才放开下一波；最后一波走完批次 COMPLETED；
 * 每个波次能查到各自的进度和统计。
 */
class WaveProgressionTest {

    private static final Logger log = LoggerFactory.getLogger(WaveProgressionTest.class);

    private RolloutOrchestrator orch;
    private DeviceRegistry devices;

    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        FirmwareRegistry firmwares = new FirmwareRegistry();
        orch = new RolloutOrchestrator(devices, firmwares,
                new CompatibilityService(), new RollbackExecutor());
        orch.registerFirmware(new FirmwarePackage("fw1", "M1", "2.0.0", "1.0.0", Map.of()));
        // 12 台设备，拆成 3 波：4 / 3 / 5
        for (int i = 1; i <= 12; i++) {
            orch.registerDevice(new Device("d" + i, "M1", "1.0.0", Map.of()));
        }
    }

    private List<List<String>> waves() {
        return List.of(
                List.of("d1", "d2", "d3", "d4"),
                List.of("d5", "d6", "d7"),
                List.of("d8", "d9", "d10", "d11", "d12"));
    }

    private void succeed(String batch, String device) {
        ProgressReportResult r = orch.report(
                new ProgressEvent(batch, device, ProgressPhase.SUCCEEDED, null, null));
        assertTrue(r.accepted(), "device " + device + " SUCCEEDED should be accepted in open wave");
    }

    @Test
    void onlyFirstWaveOpensAfterStartAndFutureWavesRejectReports() {
        orch.createBatch("w", "fw1",
                List.of("d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9", "d10", "d11", "d12"),
                100, waves());
        orch.start("w");

        BatchSnapshot s0 = orch.snapshot("w");
        log.info("[wave] after start activeWave={} waveCount={} statuses={}",
                s0.activeWaveIndex(), s0.waveCount(),
                s0.waves().stream().map(w -> w.waveIndex() + ":" + w.status()).toList());
        assertEquals(0, s0.activeWaveIndex());
        assertEquals(WaveStatus.ACTIVE, s0.waves().get(0).status());
        assertEquals(WaveStatus.PENDING, s0.waves().get(1).status());
        assertEquals(WaveStatus.PENDING, s0.waves().get(2).status());
        assertEquals(BatchStatus.ACTIVE, s0.status());

        // 第二、三波设备在第一波未结束时上报：必须拒绝，原因可区分
        BatchStateException ex = assertThrows(BatchStateException.class,
                () -> orch.report(new ProgressEvent("w", "d5", ProgressPhase.SUCCEEDED, null, null)));
        log.info("[wave] future-wave report rejected code={}", ex.getReasonCode());
        assertEquals("WAVE_NOT_OPENED", ex.getReasonCode());
        BatchStateException ex2 = assertThrows(BatchStateException.class,
                () -> orch.report(new ProgressEvent("w", "d12", ProgressPhase.DOWNLOADING, null, null)));
        assertEquals("WAVE_NOT_OPENED", ex2.getReasonCode());

        // 后续波次设备版本/状态未被触碰
        assertEquals(DeviceUpgradeState.NOT_STARTED, orch.snapshot("w").entries().stream()
                .filter(e -> e.getDeviceId().equals("d5")).findFirst().orElseThrow().getState());
    }

    @Test
    void cannotAdvanceBeforeCurrentWaveFinishedAndAdvancesInOrder() {
        orch.createBatch("w2", "fw1",
                List.of("d1", "d2", "d3", "d4", "d5", "d6", "d7"),
                100, List.of(List.of("d1", "d2"), List.of("d3", "d4"), List.of("d5", "d6", "d7")));
        orch.start("w2");

        // 一台成功、一台没结束时推进：拒绝（不跳波）
        succeed("w2", "d1");
        WaveAdvanceResult premature = orch.advanceWave("w2");
        log.info("[wave2] premature advance: advanced={} reason={} detail={}",
                premature.advanced(), premature.reasonCode(), premature.detail());
        assertFalse(premature.advanced());
        assertEquals("WAVE_NOT_FINISHED", premature.reasonCode());
        assertEquals(0, orch.snapshot("w2").activeWaveIndex(), "波次绝不能被提前放开");

        // 第一波全部结束，再推进：放开第二波
        succeed("w2", "d2");
        WaveSnapshot w1 = orch.snapshot("w2").waves().get(0);
        assertEquals(WaveStatus.FINISHED, w1.status());
        assertEquals(2, w1.succeededCount());

        WaveAdvanceResult adv1 = orch.advanceWave("w2");
        log.info("[wave2] first advance: {} {}", adv1.reasonCode(), adv1.detail());
        assertTrue(adv1.advanced());
        assertEquals(1, adv1.activeWaveIndex());
        assertEquals(WaveStatus.ACTIVE, orch.snapshot("w2").waves().get(1).status());

        // 现在第一波设备的迟到补报仍按幂等处理，第二波可以上报
        ProgressReportResult late = orch.report(
                new ProgressEvent("w2", "d1", ProgressPhase.SUCCEEDED, null, null));
        assertTrue(late.duplicate());
        assertFalse(late.accepted());
        succeed("w2", "d3");
        succeed("w2", "d4");

        WaveAdvanceResult adv2 = orch.advanceWave("w2");
        assertTrue(adv2.advanced());
        assertEquals(2, adv2.activeWaveIndex());

        succeed("w2", "d5");
        succeed("w2", "d6");
        // 最后一波最后一台成功后，批次直接 COMPLETED，无需再推进
        succeed("w2", "d7");

        BatchSnapshot done = orch.snapshot("w2");
        log.info("[wave2] final status={} activeWave={} waveStatuses={}",
                done.status(), done.activeWaveIndex(),
                done.waves().stream().map(w -> w.waveIndex() + ":" + w.status()).toList());
        assertEquals(BatchStatus.COMPLETED, done.status());
        assertEquals(7, done.succeededCount());
        for (WaveSnapshot w : done.waves()) {
            assertEquals(WaveStatus.FINISHED, w.status());
        }
    }

    @Test
    void eachWaveExposesIndependentProgressAndStats() {
        orch.createBatch("w3", "fw1",
                List.of("d1", "d2", "d3", "d4", "d5"),
                100, List.of(List.of("d1", "d2", "d3"), List.of("d4", "d5")));
        orch.start("w3");
        orch.report(new ProgressEvent("w3", "d1", ProgressPhase.DOWNLOADING, null, null));
        orch.report(new ProgressEvent("w3", "d2", ProgressPhase.SUCCEEDED, null, null));

        List<WaveSnapshot> ws = orch.snapshot("w3").waves();
        log.info("[wave3] wave0 stats total={} succ={} inProgress={} notStarted={}; wave1 notStarted={}",
                ws.get(0).totalDevices(), ws.get(0).succeededCount(),
                ws.get(0).inProgressCount(), ws.get(0).notStartedCount(),
                ws.get(1).notStartedCount());
        assertEquals(3, ws.get(0).totalDevices());
        assertEquals(1, ws.get(0).succeededCount());
        assertEquals(1, ws.get(0).inProgressCount());
        assertEquals(1, ws.get(0).notStartedCount());
        assertEquals(2, ws.get(1).notStartedCount());
        assertEquals(0, ws.get(1).succeededCount());
    }

    @Test
    void invalidWaveSplitRejectsWholeBatch() {
        List<String> all = List.of("d1", "d2", "d3");
        // 漏设备
        BatchStateException missing = assertThrows(BatchStateException.class,
                () -> orch.createBatch("bad1", "fw1", all, 100,
                        List.of(List.of("d1", "d2"))));
        assertEquals("WAVES_NOT_COVERING_ALL_DEVICES", missing.getReasonCode());
        // 重复设备
        BatchStateException dup = assertThrows(BatchStateException.class,
                () -> orch.createBatch("bad2", "fw1", all, 100,
                        List.of(List.of("d1", "d2"), List.of("d2", "d3"))));
        assertEquals("WAVE_DEVICE_DUPLICATED", dup.getReasonCode());
        // 空波
        BatchStateException empty = assertThrows(BatchStateException.class,
                () -> orch.createBatch("bad3", "fw1", all, 100,
                        List.of(List.of("d1", "d2", "d3"), List.of())));
        assertEquals("WAVE_EMPTY", empty.getReasonCode());
        // 失败后批次不得残留
        assertThrows(BatchStateException.class, () -> orch.snapshot("bad1"));
    }
}
