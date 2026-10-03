package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.exception.BatchStateException;
import com.github.feng111627.otafleetrolloutjava.model.BatchSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ProgressEvent;
import com.github.feng111627.otafleetrolloutjava.model.ProgressPhase;
import com.github.feng111627.otafleetrolloutjava.model.ProgressReportResult;
import com.github.feng111627.otafleetrolloutjava.model.RollbackOutcome;
import com.github.feng111627.otafleetrolloutjava.model.WaveRollbackSummary;
import com.github.feng111627.otafleetrolloutjava.model.WaveSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.WaveStatus;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回滚后“波次状态 + 按波次统计”与整批回滚结果的一致性（本轮缺陷修复的回归测试）：
 * <ul>
 *   <li>自动熔断回滚 / 人工回滚 / 回滚中断后重试 三条链路下，回滚结束后
 *       不再有任何波次处于 ACTIVE（进行中）；</li>
 *   <li>曾放开过的波次（ACTIVE/FINISHED）回滚后为 ROLLED_BACK，
 *       始终没轮到的波次保持 PENDING（原样没动）；</li>
 *   <li>回滚后按波次查询：已恢复 / 未开始跳过 / 需人工 三类计数各自可见、
 *       三者之和等于波次设备总数，且与整批 RollbackOutcome 的按波次汇总逐一相等，
 *       已回滚设备不再被算进“未开始”；</li>
 *   <li>重复回滚、回滚过程中与回滚之后并发查询，波次状态与计数稳定一致。</li>
 * </ul>
 */
class WaveRollbackStatusConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(WaveRollbackStatusConsistencyTest.class);

    private DeviceRegistry devices;
    private FirmwareRegistry firmwares;

    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        firmwares = new FirmwareRegistry();
    }

    /** 7 台设备 3 波：wave0=[d0,d1] wave1=[d2,d3,d4] wave2=[d5,d6] */
    private RolloutOrchestrator newSystem(DeviceRestoreOperator op, String batchId, int threshold) {
        RolloutOrchestrator orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(), new RollbackExecutor(op));
        orch.registerFirmware(new FirmwarePackage("fw", "M", "2.0.0", "1.0.0", Map.of()));
        for (int i = 0; i < 7; i++) {
            orch.registerDevice(new Device("d" + i, "M", "1.0.0", Map.of()));
        }
        orch.createBatch(batchId, "fw",
                List.of("d0", "d1", "d2", "d3", "d4", "d5", "d6"), threshold,
                List.of(List.of("d0", "d1"), List.of("d2", "d3", "d4"), List.of("d5", "d6")));
        return orch;
    }

    private ProgressEvent ev(String batch, String device, ProgressPhase phase) {
        return new ProgressEvent(batch, device, phase, null, null);
    }

    // ---------------- 通用断言 ----------------

    /** 回滚结束后：任何波次都不允许再显示“正在放量/进行中”，且没有当前放开波次 */
    private void assertNoWaveInProgress(BatchSnapshot snap) {
        for (WaveSnapshot w : snap.waves()) {
            assertTrue(w.status() != WaveStatus.ACTIVE,
                    "回滚后不允许还有波次处于 ACTIVE: " + w);
        }
        assertEquals(-1, snap.activeWaveIndex(), "回滚后不应再有当前放开波次");
    }

    /** 任意时刻：每波 7 类计数之和必须恰好等于该波设备总数（不多算、不漏算） */
    private void assertWaveCountersComplete(BatchSnapshot snap) {
        for (WaveSnapshot w : snap.waves()) {
            int sum = w.succeededCount() + w.failedCount() + w.inProgressCount() + w.notStartedCount()
                    + w.rolledBackSucceededCount() + w.rollbackSkippedCount() + w.rollbackFailedCount();
            assertEquals(w.totalDevices(), sum, "波次 " + w.waveIndex() + " 计数必须覆盖全部设备: " + w);
        }
    }

    /** 回滚完成后：每波“已恢复+未开始跳过+需人工”= 该波总数，且与整批 RollbackOutcome 按波次汇总逐一相等 */
    private void assertWaveStatsMatchOutcome(BatchSnapshot snap, RollbackOutcome outcome) {
        assertEquals(snap.waves().size(), outcome.waveSummaries().size());
        int sumRestore = 0, sumSkip = 0, sumManual = 0;
        for (int i = 0; i < snap.waves().size(); i++) {
            WaveSnapshot w = snap.waves().get(i);
            WaveRollbackSummary s = outcome.waveSummaries().get(i);
            assertEquals(w.waveIndex(), s.waveIndex());
            assertEquals(w.totalDevices(), s.totalDevices(), "wave " + i + " total");
            assertEquals(w.rolledBackSucceededCount(), s.rolledBackSucceeded(), "wave " + i + " restored");
            assertEquals(w.rollbackSkippedCount(), s.skippedNotStarted(), "wave " + i + " skipped");
            assertEquals(w.rollbackFailedCount(), s.failedDevices(), "wave " + i + " manual");
            assertEquals(w.totalDevices(), w.rollbackAccountedCount(),
                    "wave " + i + " 回滚三类计数之和必须等于该波设备总数: " + w);
            // 已回滚设备不得再被算成“未开始/进行中/成功/失败”
            assertEquals(0, w.notStartedCount(), "wave " + i + " 回滚后不应再有未开始设备");
            assertEquals(0, w.succeededCount() + w.failedCount() + w.inProgressCount(),
                    "wave " + i + " 回滚后升级态计数必须归零: " + w);
            sumRestore += s.rolledBackSucceeded();
            sumSkip += s.skippedNotStarted();
            sumManual += s.failedDevices();
        }
        // 各波之和 == 整批结果
        assertEquals(outcome.rolledBackSucceeded(), sumRestore);
        assertEquals(outcome.skippedNotStarted(), sumSkip);
        assertEquals(outcome.failedDevices(), sumManual);
        assertEquals(snap.totalDevices(), sumRestore + sumSkip + sumManual);
    }

    private void logWaves(String tag, BatchSnapshot snap) {
        for (WaveSnapshot w : snap.waves()) {
            log.info("[{}] wave={} status={} total={} 升级态(succ/fail/prog/notStarted)={}/{}/{}/{} 回滚(恢复/跳过/人工)={}/{}/{}",
                    tag, w.waveIndex(), w.status(), w.totalDevices(),
                    w.succeededCount(), w.failedCount(), w.inProgressCount(), w.notStartedCount(),
                    w.rolledBackSucceededCount(), w.rollbackSkippedCount(), w.rollbackFailedCount());
        }
    }

    // ---------------- 用例 1：自动熔断回滚 ----------------

    @Test
    void autoCircuitRollbackConvergesWaveStatusesAndStats() {
        log.info("=== 覆盖：失败率超阈自动熔断回滚后，各波次状态与按波次统计 ===");
        // 阈值 25%：wave0 两台成功后推进；wave1 1 成 1 败时已结束 3 成 1 败 = 25% 触发熔断
        RolloutOrchestrator orch = newSystem(new LocalRestoreOperator(), "auto", 25);
        orch.start("auto");
        orch.report(ev("auto", "d0", ProgressPhase.SUCCEEDED));
        orch.report(ev("auto", "d1", ProgressPhase.SUCCEEDED));
        assertEquals("ADVANCED", orch.advanceWave("auto").reasonCode());
        orch.report(ev("auto", "d2", ProgressPhase.SUCCEEDED));
        orch.report(ev("auto", "d3", ProgressPhase.INSTALLING));
        ProgressReportResult trip = orch.report(ev("auto", "d4", ProgressPhase.FAILED));
        assertTrue(trip.circuitOpened(), "失败率达阈必须触发熔断");
        assertEquals(BatchStatus.ROLLED_BACK, trip.batchStatus(), "熔断后自动回滚必须执行完成");

        BatchSnapshot snap = orch.snapshot("auto");
        logWaves("auto", snap);
        assertNoWaveInProgress(snap);
        assertWaveCountersComplete(snap);
        // 曾放开过的 wave0(FINISHED)/wave1(ACTIVE) -> ROLLED_BACK；没轮到的 wave2 保持 PENDING
        assertEquals(WaveStatus.ROLLED_BACK, snap.waves().get(0).status());
        assertEquals(WaveStatus.ROLLED_BACK, snap.waves().get(1).status());
        assertEquals(WaveStatus.PENDING, snap.waves().get(2).status());

        RollbackOutcome outcome = orch.rollback("auto"); // 幂等复现整批结果
        log.info("[auto] 整批: 恢复={} 跳过={} 人工={}, 按波次={}",
                outcome.rolledBackSucceeded(), outcome.skippedNotStarted(),
                outcome.failedDevices(), outcome.waveSummaries());
        assertEquals(new WaveRollbackSummary(0, 2, 2, 0, 0), outcome.waveSummaries().get(0));
        assertEquals(new WaveRollbackSummary(1, 3, 1, 0, 2), outcome.waveSummaries().get(1));
        assertEquals(new WaveRollbackSummary(2, 2, 0, 2, 0), outcome.waveSummaries().get(2));
        assertWaveStatsMatchOutcome(snap, outcome);

        BatchStateException adv = assertThrows(BatchStateException.class, () -> orch.advanceWave("auto"));
        assertEquals("BATCH_ROLLED_BACK", adv.getReasonCode());
        log.info("=== 用例通过：自动熔断回滚后无进行中波次，各波计数与整批一致 ===");
    }

    // ---------------- 用例 2：人工回滚（与自动熔断表现一致） ----------------

    @Test
    void manualRollbackConvergesWaveStatusesAndStats() {
        log.info("=== 覆盖：人工回滚后，各波次状态与按波次统计（与自动熔断同口径） ===");
        RolloutOrchestrator orch = newSystem(new LocalRestoreOperator(), "manual", 100);
        orch.start("manual");
        orch.report(ev("manual", "d0", ProgressPhase.SUCCEEDED));
        orch.report(ev("manual", "d1", ProgressPhase.SUCCEEDED));
        assertEquals("ADVANCED", orch.advanceWave("manual").reasonCode());
        orch.report(ev("manual", "d2", ProgressPhase.SUCCEEDED));
        orch.report(ev("manual", "d3", ProgressPhase.DOWNLOADING)); // 进行中
        // d4 已放开但未开始；wave2 整波未放开

        RollbackOutcome outcome = orch.rollback("manual");
        BatchSnapshot snap = orch.snapshot("manual");
        logWaves("manual", snap);
        log.info("[manual] 整批: 恢复={} 跳过={} 人工={}, 按波次={}",
                outcome.rolledBackSucceeded(), outcome.skippedNotStarted(),
                outcome.failedDevices(), outcome.waveSummaries());

        assertEquals(BatchStatus.ROLLED_BACK, snap.status());
        assertNoWaveInProgress(snap);
        assertWaveCountersComplete(snap);
        assertEquals(WaveStatus.ROLLED_BACK, snap.waves().get(0).status());
        assertEquals(WaveStatus.ROLLED_BACK, snap.waves().get(1).status());
        assertEquals(WaveStatus.PENDING, snap.waves().get(2).status());

        // wave0: 2 台恢复；wave1: 1 恢复(d2) + 1 人工(d3 进行中) + 1 跳过(d4)；wave2: 2 跳过
        assertEquals(new WaveRollbackSummary(0, 2, 2, 0, 0), outcome.waveSummaries().get(0));
        assertEquals(new WaveRollbackSummary(1, 3, 1, 1, 1), outcome.waveSummaries().get(1));
        assertEquals(new WaveRollbackSummary(2, 2, 0, 2, 0), outcome.waveSummaries().get(2));
        assertWaveStatsMatchOutcome(snap, outcome);

        // 重复回滚：结果与快照稳定不变
        RollbackOutcome again = orch.rollback("manual");
        assertEquals(outcome.waveSummaries(), again.waveSummaries());
        assertEquals(outcome.rolledBackSucceeded(), again.rolledBackSucceeded());
        assertEquals(outcome.skippedNotStarted(), again.skippedNotStarted());
        assertEquals(outcome.failedDevices(), again.failedDevices());
        BatchSnapshot snap2 = orch.snapshot("manual");
        assertNoWaveInProgress(snap2);
        assertWaveStatsMatchOutcome(snap2, again);
        log.info("=== 用例通过：人工回滚（含重复回滚）后无进行中波次，各波计数与整批一致 ===");
    }

    // ---------------- 用例 3：回滚中断后重试收敛 ----------------

    @Test
    void interruptedRollbackRetryConvergesWaveStatusesAndStats() {
        log.info("=== 覆盖：回滚中途报错被打断，重试后各波次状态与统计收敛到最终结果 ===");
        AtomicInteger restoreCalls = new AtomicInteger();
        DeviceRestoreOperator counting = (device, target) -> {
            restoreCalls.incrementAndGet();
            device.setCurrentVersion(target);
            return true;
        };
        InterruptibleRollbackExecutor interrupting = new InterruptibleRollbackExecutor(counting, 2);
        RolloutOrchestrator orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(), interrupting);
        orch.registerFirmware(new FirmwarePackage("fw", "M", "2.0.0", "1.0.0", Map.of()));
        for (int i = 0; i < 7; i++) {
            orch.registerDevice(new Device("d" + i, "M", "1.0.0", Map.of()));
        }
        orch.createBatch("retry", "fw",
                List.of("d0", "d1", "d2", "d3", "d4", "d5", "d6"), 100,
                List.of(List.of("d0", "d1"), List.of("d2", "d3", "d4"), List.of("d5", "d6")));
        orch.start("retry");
        orch.report(ev("retry", "d0", ProgressPhase.SUCCEEDED));
        orch.report(ev("retry", "d1", ProgressPhase.SUCCEEDED));
        assertEquals("ADVANCED", orch.advanceWave("retry").reasonCode());
        orch.report(ev("retry", "d2", ProgressPhase.SUCCEEDED));
        orch.report(ev("retry", "d3", ProgressPhase.FAILED));

        // 第一次回滚在处理 2 台后被打断
        assertThrows(IllegalStateException.class, () -> orch.rollback("retry"));
        BatchSnapshot mid = orch.snapshot("retry");
        logWaves("retry-mid", mid);
        assertEquals(BatchStatus.CIRCUIT_OPEN, mid.status(), "中断后批次必须停留 CIRCUIT_OPEN");
        // 半途中也不允许有波次挂在“进行中”，且每台设备都必须被某一类计数覆盖
        assertNoWaveInProgress(mid);
        assertWaveCountersComplete(mid);
        assertEquals(WaveStatus.ROLLED_BACK, mid.waves().get(0).status());
        assertEquals(WaveStatus.ROLLED_BACK, mid.waves().get(1).status());
        assertEquals(WaveStatus.PENDING, mid.waves().get(2).status());

        // 重试：接着处理剩下的设备
        interrupting.setInterruptEnabled(false);
        RollbackOutcome outcome = orch.rollback("retry");
        BatchSnapshot fin = orch.snapshot("retry");
        logWaves("retry-final", fin);
        log.info("[retry] 整批: 恢复={} 跳过={} 人工={}, restoreCalls={}",
                outcome.rolledBackSucceeded(), outcome.skippedNotStarted(),
                outcome.failedDevices(), restoreCalls.get());

        assertEquals(BatchStatus.ROLLED_BACK, fin.status());
        assertNoWaveInProgress(fin);
        assertWaveCountersComplete(fin);
        // 最终收敛：d0,d1,d2 恢复；d3 人工；d4,d5,d6 跳过 —— 与“一次性回滚到底”完全一致
        assertEquals(new WaveRollbackSummary(0, 2, 2, 0, 0), outcome.waveSummaries().get(0));
        assertEquals(new WaveRollbackSummary(1, 3, 1, 1, 1), outcome.waveSummaries().get(1));
        assertEquals(new WaveRollbackSummary(2, 2, 0, 2, 0), outcome.waveSummaries().get(2));
        assertWaveStatsMatchOutcome(fin, outcome);
        assertEquals(3, restoreCalls.get(), "中断重试不得对同一设备重复恢复");

        // 收敛后再回滚：幂等复现，不再触发恢复
        RollbackOutcome again = orch.rollback("retry");
        assertEquals(outcome.waveSummaries(), again.waveSummaries());
        assertEquals(3, restoreCalls.get());
        assertWaveStatsMatchOutcome(orch.snapshot("retry"), again);
        log.info("=== 用例通过：中断重试后各波次状态与统计收敛，无半推半退 ===");
    }

    // ---------------- 用例 4：回滚过程中/之后并发查询稳定一致 ----------------

    @Test
    void concurrentQueriesDuringAndAfterRollbackAreStable() throws Exception {
        log.info("=== 覆盖：回滚过程中与回滚之后并发查批次，波次状态与计数稳定一致 ===");
        CountDownLatch restoreStarted = new CountDownLatch(1);
        CountDownLatch allowRestoreFinish = new CountDownLatch(1);
        DeviceRestoreOperator blocking = (device, target) -> {
            restoreStarted.countDown();
            try {
                allowRestoreFinish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            device.setCurrentVersion(target);
            return true;
        };
        RolloutOrchestrator orch = newSystem(blocking, "conc", 100);
        orch.start("conc");
        orch.report(ev("conc", "d0", ProgressPhase.SUCCEEDED));
        orch.report(ev("conc", "d1", ProgressPhase.SUCCEEDED));
        assertEquals("ADVANCED", orch.advanceWave("conc").reasonCode());
        orch.report(ev("conc", "d2", ProgressPhase.SUCCEEDED));

        // 后台线程执行回滚，恢复 I/O 被门闩挡住 => 主线程在“回滚过程中”并发查询
        ExecutorService pool = Executors.newFixedThreadPool(9);
        Future<RollbackOutcome> rollbackFuture = pool.submit(() -> orch.rollback("conc"));
        assertTrue(restoreStarted.await(10, TimeUnit.SECONDS), "回滚应已开始恢复第一台设备");

        for (int i = 0; i < 50; i++) {
            BatchSnapshot during = orch.snapshot("conc");
            assertEquals(BatchStatus.CIRCUIT_OPEN, during.status());
            assertNoWaveInProgress(during);      // 回滚过程中也不得有波次显示进行中
            assertWaveCountersComplete(during);  // 每台设备都被某一类计数覆盖
        }
        log.info("[conc] 回滚过程中 50 次并发快照：无 ACTIVE 波次、计数全覆盖");
        allowRestoreFinish.countDown();
        RollbackOutcome outcome = rollbackFuture.get(10, TimeUnit.SECONDS);
        assertEquals(BatchStatus.ROLLED_BACK, orch.snapshot("conc").status());

        // 回滚之后：8 线程同一起跑线并发 “查快照 + 重复回滚”，所有观察必须一致
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> readers = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            readers.add(pool.submit(() -> {
                try {
                    gate.await();
                    for (int i = 0; i < 20; i++) {
                        BatchSnapshot snap = orch.snapshot("conc");
                        assertNoWaveInProgress(snap);
                        assertWaveCountersComplete(snap);
                        assertWaveStatsMatchOutcome(snap, outcome);
                        RollbackOutcome o = orch.rollback("conc"); // 重复回滚幂等
                        assertEquals(outcome.waveSummaries(), o.waveSummaries());
                        assertEquals(outcome.rolledBackSucceeded(), o.rolledBackSucceeded());
                        assertEquals(outcome.skippedNotStarted(), o.skippedNotStarted());
                        assertEquals(outcome.failedDevices(), o.failedDevices());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
        }
        gate.countDown();
        for (Future<?> f : readers) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        logWaves("conc-final", orch.snapshot("conc"));
        log.info("=== 用例通过：回滚过程中/之后并发查询与重复回滚，波次状态与计数稳定一致 ===");
    }

    // ---------------- 用例 5：从未放开任何波次就回滚 ----------------

    @Test
    void rollbackBeforeAnyWaveOpenedKeepsAllWavesPending() {
        log.info("=== 覆盖：批次从未 start 就人工回滚，所有波次保持“未开始” ===");
        RolloutOrchestrator orch = newSystem(new LocalRestoreOperator(), "cold", 50);
        RollbackOutcome outcome = orch.rollback("cold"); // PENDING 批次直接回滚
        BatchSnapshot snap = orch.snapshot("cold");
        logWaves("cold", snap);

        assertEquals(BatchStatus.ROLLED_BACK, snap.status());
        assertNoWaveInProgress(snap);
        assertWaveCountersComplete(snap);
        for (WaveSnapshot w : snap.waves()) {
            assertEquals(WaveStatus.PENDING, w.status(), "从未放开的波次必须保持 PENDING: " + w);
            assertEquals(w.totalDevices(), w.rollbackSkippedCount(), "整波设备都应是未开始跳过: " + w);
            assertEquals(0, w.rolledBackSucceededCount() + w.rollbackFailedCount());
        }
        assertEquals(7, outcome.skippedNotStarted());
        assertEquals(0, outcome.rolledBackSucceeded());
        assertEquals(0, outcome.failedDevices());
        assertWaveStatsMatchOutcome(snap, outcome);
        log.info("=== 用例通过：未放开过的波次回滚后依然“未开始”，进度未被带跑 ===");
    }
}
