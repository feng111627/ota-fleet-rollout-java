package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.exception.BatchStateException;
import com.github.feng111627.otafleetrolloutjava.model.BatchSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ProgressEvent;
import com.github.feng111627.otafleetrolloutjava.model.ProgressPhase;
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
 * 波次批次的暂停/恢复：暂停后无论哪一波都不再接受进度上报（心跳除外），
 * 暂停期间推进波次也被禁止；恢复后从停下的当前波继续，后续波次顺序不变。
 */
class WavePauseResumeTest {

    private static final Logger log = LoggerFactory.getLogger(WavePauseResumeTest.class);

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
        orch.createBatchWithWaveSizes("p", "fw1",
                List.of("d1", "d2", "d3", "d4", "d5", "d6"), 100, List.of(2, 2, 2));
        orch.start("p");
    }

    private void succeed(String d) {
        orch.report(new ProgressEvent("p", d, ProgressPhase.SUCCEEDED, null, null));
    }

    @Test
    void pauseBlocksReportsAndAdvanceForEveryWaveResumeContinuesInPlace() {
        succeed("d1");
        orch.pause("p");
        log.info("[pause] batch paused at wave 0; current/other wave reports and advance must be blocked");

        // 当前波设备的新上报被拒
        BatchStateException current = assertThrows(BatchStateException.class,
                () -> succeed("d2"));
        assertEquals("BATCH_PAUSED", current.getReasonCode());
        // 后波设备上报同样被拒（暂停对所有波次一起生效）
        BatchStateException future = assertThrows(BatchStateException.class,
                () -> succeed("d3"));
        assertEquals("BATCH_PAUSED", future.getReasonCode());
        // 暂停期间不允许推进波次
        BatchStateException advance = assertThrows(BatchStateException.class,
                () -> orch.advanceWave("p"));
        assertEquals("BATCH_NOT_ACTIVE", advance.getReasonCode());
        // 心跳仍然接受
        assertFalse(orch.report(ProgressEvent.heartbeat("p", "d4")).accepted());

        BatchSnapshot paused = orch.snapshot("p");
        assertEquals(BatchStatus.PAUSED, paused.status());
        assertEquals(0, paused.currentWaveNo(), "暂停不得改变当前波次");
        assertEquals(1, paused.succeededCount());

        // 恢复后从第一波停下的地方继续，波次顺序不丢
        orch.resume("p");
        succeed("d2");
        assertTrue(orch.advanceWave("p").advanced());
        assertEquals(WaveStatus.FINISHED, orch.waveSnapshots("p").get(0).status());
        assertEquals(WaveStatus.ACTIVE, orch.waveSnapshots("p").get(1).status());

        // 第二波走一遍再暂停/恢复，确认恢复点始终是当前波
        succeed("d3");
        orch.pause("p");
        BatchStateException pausedAgain = assertThrows(BatchStateException.class,
                () -> succeed("d4"));
        assertEquals("BATCH_PAUSED", pausedAgain.getReasonCode());
        orch.resume("p");
        succeed("d4");
        assertTrue(orch.advanceWave("p").advanced());

        succeed("d5");
        succeed("d6");
        assertTrue(orch.advanceWave("p").completed());

        BatchSnapshot done = orch.snapshot("p");
        assertEquals(BatchStatus.COMPLETED, done.status());
        assertEquals(6, done.succeededCount());
        List<WaveSnapshot> waves = orch.waveSnapshots("p");
        for (WaveSnapshot w : waves) {
            assertEquals(2, w.succeeded());
            assertEquals(WaveStatus.FINISHED, w.status());
        }
        log.info("[pause] resumed and completed all 3 waves, each succeeded=2");
    }
}
