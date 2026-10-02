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
import com.github.feng111627.otafleetrolloutjava.model.WaveRollbackOutcome;
import com.github.feng111627.otafleetrolloutjava.model.WaveSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.WaveStatus;
import com.github.feng111627.otafleetrolloutjava.registry.DeviceRegistry;
import com.github.feng111627.otafleetrolloutjava.registry.FirmwareRegistry;
import com.github.feng111627.otafleetrolloutjava.rollout.LocalRestoreOperator;
import com.github.feng111627.otafleetrolloutjava.rollout.RollbackAbortTrigger;
import com.github.feng111627.otafleetrolloutjava.rollout.RollbackExecutor;
import com.github.feng111627.otafleetrolloutjava.rollout.RolloutOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 波次与熔断回滚的衔接：
 * <ul>
 *   <li>某波熔断进入回滚后，后面未开始的波次原地不动（保持 PENDING）、不会被放开；</li>
 *   <li>回滚把各波已升级成功设备恢复到各自建批原版本，未开始跳过，状态不明标人工，
 *       并按波次分别给出处置结果；</li>
 *   <li>回滚完成后批次禁止再推进波次；</li>
 *   <li>回滚中途被打断后重试：已处理不重复、未处理继续，最终无半推半退；</li>
 *   <li>回滚之后的迟到/重复上报与只报一次一致。</li>
 * </ul>
 */
class WaveRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(WaveRollbackTest.class);

    private DeviceRegistry devices;
    private FirmwareRegistry firmwares;

    /**
     * 三波，每波 3 台，设备原始版本各不相同以验证“恢复到各自原版本”：
     * wave0: d1(1.1.0) d2(1.2.0) d3(1.3.0)
     * wave1: d4(1.1.0) d5(1.2.0) d6(1.3.0)
     * wave2: d7(1.1.0) d8(1.2.0) d9(1.3.0)
     */
    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        firmwares = new FirmwareRegistry();
    }

    private RolloutOrchestrator newSystem(RollbackExecutor executor) {
        RolloutOrchestrator orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(), executor);
        orch.registerFirmware(new FirmwarePackage("fw1", "M1", "2.0.0", "1.0.0", Map.of()));
        String[] ids = {"d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9"};
        for (int i = 0; i < ids.length; i++) {
            String version = "1." + (i % 3 + 1) + ".0"; // 1.1.0 / 1.2.0 / 1.3.0
            orch.registerDevice(new Device(ids[i], "M1", version, Map.of()));
        }
        return orch;
    }

    private List<String> ids() {
        return List.of("d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9");
    }

    private ProgressReportResult report(RolloutOrchestrator orch, String device, ProgressPhase phase, String msg, String eid) {
        return orch.report(new ProgressEvent("rb", device, phase, msg, eid));
    }

    @Test
    void circuitInFirstWaveFreezesLaterWavesAndRollsBackPerWave() {
        RolloutOrchestrator orch = newSystem(new RollbackExecutor(new LocalRestoreOperator()));
        orch.createBatchWithWaveSizes("rb", "fw1", ids(), 50, List.of(3, 3, 3));
        orch.start("rb");

        // 第一波：d1 成功、d2 安装中（状态不明）、d3 失败 => 失败率 1/2=50% 熔断
        report(orch, "d1", ProgressPhase.SUCCEEDED, null, null);
        report(orch, "d2", ProgressPhase.INSTALLING, null, null);
        ProgressReportResult trip = report(orch, "d3", ProgressPhase.FAILED, "flash fail", null);
        assertTrue(trip.circuitOpened());
        assertEquals(BatchStatus.ROLLED_BACK, trip.batchStatus());

        List<WaveSnapshot> waves = orch.waveSnapshots("rb");
        log.info("[rb] after circuit wave statuses: {}, {}, {}",
                waves.get(0).status(), waves.get(1).status(), waves.get(2).status());
        // 第一波已放开过 -> ROLLED_BACK；后两波从未放开 -> 保持 PENDING，原地未动
        assertEquals(WaveStatus.ROLLED_BACK, waves.get(0).status());
        assertEquals(WaveStatus.PENDING, waves.get(1).status());
        assertEquals(WaveStatus.PENDING, waves.get(2).status());

        // 后波设备仍不接受上报，且不能推进波次
        BatchStateException blocked = assertThrows(BatchStateException.class,
                () -> report(orch, "d4", ProgressPhase.DOWNLOADING, null, null));
        assertEquals("BATCH_ROLLED_BACK", blocked.getReasonCode());
        var noAdvance = orch.advanceWave("rb");
        assertFalse(noAdvance.advanced());
        assertEquals(BatchStatus.ROLLED_BACK, noAdvance.batchStatus());

        // 回滚结果按波次分别给出
        RollbackOutcome outcome = orch.rollback("rb"); // 幂等重入
        assertEquals(3, outcome.waveResults().size());
        Map<Integer, WaveRollbackOutcome> byWave = new LinkedHashMap<>();
        outcome.waveResults().forEach(w -> byWave.put(w.waveNo(), w));

        // 第一波：d1 恢复成功，d2 安装中标人工，d3 升级失败标人工
        WaveRollbackOutcome w0 = byWave.get(0);
        assertEquals(1, w0.rolledBack());
        assertEquals(0, w0.skipped());
        assertEquals(2, w0.failed());
        Map<String, RollbackDeviceResult> r0 = new LinkedHashMap<>();
        w0.deviceResults().forEach(r -> r0.put(r.deviceId(), r));
        assertEquals(DeviceUpgradeState.ROLLBACK_SUCCEEDED, r0.get("d1").outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_FAILED, r0.get("d2").outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_FAILED, r0.get("d3").outcome());

        // 第二、三波设备全部 NOT_STARTED -> 全部跳过，波次仍 PENDING
        for (int waveNo = 1; waveNo <= 2; waveNo++) {
            WaveRollbackOutcome w = byWave.get(waveNo);
            assertEquals(3, w.skipped());
            assertEquals(0, w.rolledBack());
            assertEquals(0, w.failed());
            assertEquals(WaveStatus.PENDING, w.status());
            w.deviceResults().forEach(r ->
                    assertEquals(DeviceUpgradeState.ROLLBACK_SKIPPED, r.outcome()));
        }

        // 版本恢复到“各自建批时的原版本”，未开始的保持原版本
        assertEquals("1.1.0", devices.get("d1").getCurrentVersion(), "d1 恢复到它自己的原版本");
        assertEquals("1.2.0", devices.get("d2").getCurrentVersion(), "d2 未升成功，保持原版本");
        assertEquals("1.3.0", devices.get("d3").getCurrentVersion());
        assertEquals("1.1.0", devices.get("d4").getCurrentVersion(), "d4 失败未升级，保持原版本");
        assertEquals("1.3.0", devices.get("d9").getCurrentVersion());

        // 回滚后的迟到/重复上报：d1 重复成功上报幂等；d4 的全新上报被拒
        ProgressReportResult dup = report(orch, "d1", ProgressPhase.SUCCEEDED, null, null);
        assertTrue(dup.duplicate());
        assertFalse(dup.accepted());
        BatchStateException lateNew = assertThrows(BatchStateException.class,
                () -> report(orch, "d4", ProgressPhase.SUCCEEDED, null, "late-d4"));
        assertEquals("BATCH_ROLLED_BACK", lateNew.getReasonCode());

        BatchSnapshot snapshot = orch.snapshot("rb");
        assertTrue(snapshot.rollbackDone());
        log.info("[rb] per-wave rollback rolledBack/skipped/failed: "
                        + "w0={}/{}/{} w1={}/{}/{} w2={}/{}/{}",
                w0.rolledBack(), w0.skipped(), w0.failed(),
                byWave.get(1).rolledBack(), byWave.get(1).skipped(), byWave.get(1).failed(),
                byWave.get(2).rolledBack(), byWave.get(2).skipped(), byWave.get(2).failed());
    }

    @Test
    void interruptedRollbackResumesAndDoesNotRedoProcessedDevices() {
        // 中止钩子：回滚按 d1..d9 顺序处置；d1/d2 已恢复、d3 已标记人工后，在尚未开始的
        // d4 处置前抛异常，模拟整轮中途报错/被打断。只触发一次，重试时不再中止。
        RollbackAbortTrigger onceAbort = new RollbackAbortTrigger() {
            private boolean armed = true;
            @Override
            public void check(String batchId, String deviceId, DeviceUpgradeState before) {
                if (armed && "d4".equals(deviceId)) {
                    armed = false;
                    log.info("[abort] simulating rollback interruption before device d4");
                    throw new RuntimeException("simulated rollback interrupted at d4");
                }
            }
        };
        RollbackExecutor executor = new RollbackExecutor(new LocalRestoreOperator(), onceAbort);
        RolloutOrchestrator orch = newSystem(executor);
        orch.createBatchWithWaveSizes("rb", "fw1", ids(), 50, List.of(3, 3, 3));
        orch.start("rb");
        // 全局失败率口径：d1 成功、d2 失败 => 1/2=50% 熔断
        report(orch, "d1", ProgressPhase.SUCCEEDED, null, null); // 升到 2.0.0
        try {
            // d2 FAILED 触发熔断，并在同一次调用内自动回滚；回滚在 d4 处置前被打断，
            // 异常向上抛出。d1 已恢复，d2 已标记人工，批次停在 CIRCUIT_OPEN。
            report(orch, "d2", ProgressPhase.FAILED, "boom", null);
            throw new AssertionError("expected rollback interruption to propagate");
        } catch (RuntimeException interrupted) {
            log.info("[abort] auto rollback threw as expected: {}", interrupted.getMessage());
        }

        BatchSnapshot mid = orch.snapshot("rb");
        assertEquals(BatchStatus.CIRCUIT_OPEN, mid.status(), "回滚未完成，批次应停在 CIRCUIT_OPEN");
        assertEquals("1.1.0", devices.get("d1").getCurrentVersion(), "d1 已恢复，不重复处理");
        assertEquals("1.2.0", devices.get("d2").getCurrentVersion(), "d2 升级失败，保持原版本");
        assertEquals("1.3.0", devices.get("d3").getCurrentVersion(), "d3 未开始，保持原版本");

        // 重试回滚：d1/d2 已是回滚终态被跳过（不再触发钩子/恢复），d3..d9 继续处置到完成
        RollbackOutcome outcome = orch.rollback("rb");
        Map<String, RollbackDeviceResult> results = new LinkedHashMap<>();
        outcome.deviceResults().forEach(r -> results.put(r.deviceId(), r));

        assertEquals(DeviceUpgradeState.ROLLBACK_SUCCEEDED, results.get("d1").outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_FAILED, results.get("d2").outcome());
        assertEquals(DeviceUpgradeState.ROLLBACK_SKIPPED, results.get("d3").outcome());
        for (String d : List.of("d4", "d5", "d6", "d7", "d8", "d9")) {
            assertEquals(DeviceUpgradeState.ROLLBACK_SKIPPED, results.get(d).outcome(),
                    d + " 未开始应跳过");
        }
        assertEquals(BatchStatus.ROLLED_BACK, orch.snapshot("rb").status());

        // 再重试一次：结果完全一致（幂等），版本不被二次改动
        RollbackOutcome again = orch.rollback("rb");
        assertEquals(outcome.rolledBackSucceeded(), again.rolledBackSucceeded());
        assertEquals(outcome.skippedNotStarted(), again.skippedNotStarted());
        assertEquals(outcome.failedDevices(), again.failedDevices());
        assertEquals("1.1.0", devices.get("d1").getCurrentVersion());
        assertEquals("1.2.0", devices.get("d2").getCurrentVersion());
        log.info("[abort] retry completed: rolledBack={} skipped={} failed={}, no half state",
                outcome.rolledBackSucceeded(), outcome.skippedNotStarted(), outcome.failedDevices());
    }

    @Test
    void circuitInMiddleWaveRollsBackAllOpenedWavesAndFreezesRest() {
        // 走通第一波，第二波熔断：已放开的两波一起回滚，第三波原地不动
        RolloutOrchestrator orch = newSystem(new RollbackExecutor(new LocalRestoreOperator()));
        orch.createBatchWithWaveSizes("rb", "fw1", ids(), 50, List.of(3, 3, 3));
        orch.start("rb");
        for (String d : List.of("d1", "d2", "d3")) {
            report(orch, d, ProgressPhase.SUCCEEDED, null, null);
        }
        assertTrue(orch.advanceWave("rb").advanced());
        // 第二波：全局口径已有 3 成功，再来 d4/d5/d6 三台失败 => 3/(3+3)=50% 熔断
        report(orch, "d4", ProgressPhase.FAILED, "x", null); // 25%
        report(orch, "d5", ProgressPhase.FAILED, "x", null); // 40%
        ProgressReportResult trip = report(orch, "d6", ProgressPhase.FAILED, "x", null); // 50%
        assertTrue(trip.circuitOpened());

        RollbackOutcome outcome = orch.rollback("rb");
        Map<Integer, WaveRollbackOutcome> byWave = new LinkedHashMap<>();
        outcome.waveResults().forEach(w -> byWave.put(w.waveNo(), w));
        // 第一波 3 台都成功 => 全部恢复
        assertEquals(3, byWave.get(0).rolledBack());
        // 第二波 d4/d5/d6 全部 FAILED => 均状态不明，标记人工
        assertEquals(0, byWave.get(1).rolledBack());
        assertEquals(3, byWave.get(1).failed());
        assertEquals(0, byWave.get(1).skipped());
        // 第三波从未放开，全部跳过且保持 PENDING
        assertEquals(3, byWave.get(2).skipped());
        assertEquals(WaveStatus.PENDING, byWave.get(2).status());
        // 版本核对
        assertEquals("1.1.0", devices.get("d1").getCurrentVersion());
        assertEquals("1.1.0", devices.get("d4").getCurrentVersion(), "d4 失败未升级，保持原版本");
        assertEquals("1.2.0", devices.get("d5").getCurrentVersion());
        assertEquals("1.3.0", devices.get("d9").getCurrentVersion());
        log.info("[mid] middle-wave circuit rolled back opened waves and froze the last");
    }
}
