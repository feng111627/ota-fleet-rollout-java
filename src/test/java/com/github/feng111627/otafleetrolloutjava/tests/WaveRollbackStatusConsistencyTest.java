package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.model.BatchSnapshot;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ProgressEvent;
import com.github.feng111627.otafleetrolloutjava.model.ProgressPhase;
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
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * 回滚后波次状态与统计一致性测试（针对“回滚后波次还挂在进行中、
 * 已回滚设备被算成未开始”的修复）：
 * <ul>
 *   <li>自动熔断回滚：回滚结束后没有任何波次处于 ACTIVE，已放开波次收敛为
 *       ROLLED_BACK，未放开波次保持 PENDING；</li>
 *   <li>人工回滚：与自动熔断入口表现一致；</li>
 *   <li>回滚中断后重试：各波次状态与统计收敛到最终结果，不停在半推半退；</li>
 *   <li>重复回滚 + 回滚过程中/之后并发查询：波次状态与计数稳定一致；</li>
 *   <li>每个波次的“恢复/跳过/人工”三口径之和等于波次设备总数，
 *       且与整批回滚处置（RollbackOutcome.waveSummaries）一致。</li>
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

    /** 3 波：wave0=[d0,d1,d2] wave1=[d3,d4,d5] wave2=[d6,d7] */
    private RolloutOrchestrator newSystem(RollbackExecutor executor) {
        RolloutOrchestrator orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(), executor);
        orch.registerFirmware(new FirmwarePackage("fw", "M", "2.0.0", "1.0.0", Map.of()));
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

    private ProgressEvent ev(String batch, String device, ProgressPhase phase) {
        return new ProgressEvent(batch, device, phase, null, null);
    }

    // ---------------- 通用断言 ----------------

    /** 不变量：任何时刻每波 7 个计数之和都等于波次设备总数（回滚中途也成立） */
    private void assertWaveAccountingInvariant(BatchSnapshot snap, String batch) {
        for (WaveSnapshot w : snap.waves()) {
            int sum = w.succeededCount() + w.failedCount() + w.inProgressCount()
                    + w.notStartedCount() + w.rolledBackCount() + w.skippedCount()
                    + w.needsManualCount();
            assertEquals(w.totalDevices(), sum,
                    batch + " wave " + w.waveIndex() + " 计数之和必须等于波次设备总数");
        }
    }

    /** 回滚结束后的波次状态断言：没有任何波次“进行中”，已放开的 ROLLED_BACK，没轮到的 PENDING */
    private void assertNoWaveInProgressAfterRollback(BatchSnapshot snap, int openedWaves, String batch) {
        assertEquals(BatchStatus.ROLLED_BACK, snap.status());
        assertEquals(-1, snap.activeWaveIndex(), batch + " 回滚后不应再有当前放开波次");
        for (WaveSnapshot w : snap.waves()) {
            assertTrue(w.status() != WaveStatus.ACTIVE,
                    batch + " wave " + w.waveIndex() + " 回滚后不得仍处于 ACTIVE（进行中）");
            if (w.waveIndex() < openedWaves) {
                assertEquals(WaveStatus.ROLLED_BACK, w.status(),
                        batch + " wave " + w.waveIndex() + " 已放开过，应收敛为 ROLLED_BACK");
            } else {
                assertEquals(WaveStatus.PENDING, w.status(),
                        batch + " wave " + w.waveIndex() + " 从未放开，应保持 PENDING（未开始）");
            }
            // 回滚后升级期口径清零，回滚三口径承接全部设备
            assertEquals(0, w.succeededCount());
            assertEquals(0, w.failedCount());
            assertEquals(0, w.inProgressCount());
            assertEquals(0, w.notStartedCount(),
                    batch + " wave " + w.waveIndex() + " 已回滚设备不得被算成未开始");
            assertEquals(w.totalDevices(),
                    w.rolledBackCount() + w.skippedCount() + w.needsManualCount(),
                    batch + " wave " + w.waveIndex() + " 恢复+跳过+人工 必须等于波次设备总数");
        }
    }

    /** 按波次快照统计必须与整批回滚处置结果（waveSummaries）一致 */
    private void assertWaveStatsMatchOutcome(BatchSnapshot snap, RollbackOutcome outcome, String batch) {
        assertEquals(snap.waves().size(), outcome.waveSummaries().size());
        int rolled = 0, skipped = 0, manual = 0;
        for (int i = 0; i < snap.waves().size(); i++) {
            WaveSnapshot w = snap.waves().get(i);
            WaveRollbackSummary s = outcome.waveSummaries().get(i);
            log.info("[{}] wave {} snapshot(rolled={}, skipped={}, manual={}) vs outcome{}",
                    batch, w.waveIndex(), w.rolledBackCount(), w.skippedCount(),
                    w.needsManualCount(), s);
            assertEquals(w.waveIndex(), s.waveIndex());
            assertEquals(w.totalDevices(), s.totalDevices());
            assertEquals(w.rolledBackCount(), s.rolledBackSucceeded(),
                    batch + " wave " + i + " 恢复数与整批结果不一致");
            assertEquals(w.skippedCount(), s.skippedNotStarted(),
                    batch + " wave " + i + " 跳过数与整批结果不一致");
            assertEquals(w.needsManualCount(), s.failedDevices(),
                    batch + " wave " + i + " 人工数与整批结果不一致");
            rolled += w.rolledBackCount();
            skipped += w.skippedCount();
            manual += w.needsManualCount();
        }
        assertEquals(outcome.rolledBackSucceeded(), rolled, batch + " 整批恢复数与各波之和不一致");
        assertEquals(outcome.skippedNotStarted(), skipped, batch + " 整批跳过数与各波之和不一致");
        assertEquals(outcome.failedDevices(), manual, batch + " 整批人工数与各波之和不一致");
    }

    // ---------------- 用例 ----------------

    @Test
    void autoCircuitRollbackConvergesWaveStatusAndStats() {
        log.info("=== 用例：自动熔断回滚后，各波次状态与统计收敛 ===");
        RolloutOrchestrator orch = newSystem(new RollbackExecutor(new LocalRestoreOperator()));
        orch.createBatch("ac", "fw", all(), 20, waves());
        orch.start("ac");
        // wave0 全部成功并推进；wave1：d3 成功、d4 安装中、d5 失败 -> 4 成 1 败 = 20% 触发熔断
        for (String d : List.of("d0", "d1", "d2")) {
            orch.report(ev("ac", d, ProgressPhase.SUCCEEDED));
        }
        orch.advanceWave("ac");
        orch.report(ev("ac", "d3", ProgressPhase.SUCCEEDED));
        orch.report(ev("ac", "d4", ProgressPhase.INSTALLING));
        assertTrue(orch.report(ev("ac", "d5", ProgressPhase.FAILED)).circuitOpened());

        BatchSnapshot snap = orch.snapshot("ac");
        log.info("[ac] 回滚后波次状态: {}", snap.waves().stream()
                .map(w -> "wave" + w.waveIndex() + "=" + w.status()).toList());
        assertNoWaveInProgressAfterRollback(snap, 2, "ac");
        assertWaveAccountingInvariant(snap, "ac");

        // 按波次核对：wave0 三台全恢复；wave1 d3 恢复、d4/d5 人工；wave2 两台跳过
        assertEquals(3, snap.waves().get(0).rolledBackCount());
        assertEquals(1, snap.waves().get(1).rolledBackCount());
        assertEquals(2, snap.waves().get(1).needsManualCount());
        assertEquals(2, snap.waves().get(2).skippedCount());

        RollbackOutcome outcome = orch.rollback("ac"); // 幂等复现整批结果
        assertWaveStatsMatchOutcome(snap, outcome, "ac");
        log.info("=== 用例通过：自动熔断回滚后无波次进行中，各波计数与整批结果一致 ===");
    }

    @Test
    void manualRollbackBehavesSameAsAutoCircuit() {
        log.info("=== 用例：人工回滚与自动熔断入口表现一致 ===");
        RolloutOrchestrator orch = newSystem(new RollbackExecutor(new LocalRestoreOperator()));
        // 阈值 100 永不熔断；wave0 进行中（d0 成功、d1 失败、d2 未开始）时人工回滚
        orch.createBatch("mr", "fw", all(), 100, waves());
        orch.start("mr");
        orch.report(ev("mr", "d0", ProgressPhase.SUCCEEDED));
        orch.report(ev("mr", "d1", ProgressPhase.FAILED));

        RollbackOutcome outcome = orch.rollback("mr");
        log.info("[mr] 人工回滚整批结果: rolled={} skipped={} manual={}",
                outcome.rolledBackSucceeded(), outcome.skippedNotStarted(),
                outcome.failedDevices());

        BatchSnapshot snap = orch.snapshot("mr");
        log.info("[mr] 回滚后波次状态: {}", snap.waves().stream()
                .map(w -> "wave" + w.waveIndex() + "=" + w.status()).toList());
        // 只有 wave0 放开过；wave1/wave2 从未轮到，保持 PENDING
        assertNoWaveInProgressAfterRollback(snap, 1, "mr");
        assertWaveAccountingInvariant(snap, "mr");

        // wave0：d0 恢复、d1 人工、d2 跳过；wave1 三台跳过；wave2 两台跳过
        assertEquals(1, snap.waves().get(0).rolledBackCount());
        assertEquals(1, snap.waves().get(0).needsManualCount());
        assertEquals(1, snap.waves().get(0).skippedCount());
        assertEquals(3, snap.waves().get(1).skippedCount());
        assertEquals(2, snap.waves().get(2).skippedCount());

        assertWaveStatsMatchOutcome(snap, outcome, "mr");
        log.info("=== 用例通过：人工回滚与自动熔断的波次状态/统计口径一致 ===");
    }

    @Test
    void interruptedRollbackRetryConvergesWaveStatusAndStats() {
        log.info("=== 用例：回滚中断后重试，各波次状态与统计收敛到最终结果 ===");
        AtomicInteger restoreCalls = new AtomicInteger();
        DeviceRestoreOperator counting = (device, target) -> {
            restoreCalls.incrementAndGet();
            device.setCurrentVersion(target);
            return true;
        };
        InterruptibleRollbackExecutor interrupting = new InterruptibleRollbackExecutor(counting, 2);
        RolloutOrchestrator orch = newSystem(interrupting);
        orch.createBatch("ir", "fw", all(), 100, waves());
        orch.start("ir");
        // wave0：d0,d1 成功、d2 失败（阈值 100 不熔断）-> wave0 FINISHED，推进到 wave1，d3 下载中
        orch.report(ev("ir", "d0", ProgressPhase.SUCCEEDED));
        orch.report(ev("ir", "d1", ProgressPhase.SUCCEEDED));
        orch.report(ev("ir", "d2", ProgressPhase.FAILED));
        orch.advanceWave("ir");
        orch.report(ev("ir", "d3", ProgressPhase.DOWNLOADING));

        // 第一次回滚在处理 2 台后被打断
        assertThrows(IllegalStateException.class, () -> orch.rollback("ir"));
        BatchSnapshot mid = orch.snapshot("ir");
        log.info("[ir] 中断时波次状态: {}", mid.waves().stream()
                .map(w -> "wave" + w.waveIndex() + "=" + w.status()).toList());
        assertEquals(BatchStatus.CIRCUIT_OPEN, mid.status());
        // 即便中断在中途，也不允许任何波次停在“进行中”
        for (WaveSnapshot w : mid.waves()) {
            assertTrue(w.status() != WaveStatus.ACTIVE,
                    "中断中途 wave " + w.waveIndex() + " 不得停在 ACTIVE");
        }
        assertWaveAccountingInvariant(mid, "ir");

        // 重试把剩下的处理完
        interrupting.setInterruptEnabled(false);
        RollbackOutcome outcome = orch.rollback("ir");
        BatchSnapshot fin = orch.snapshot("ir");
        log.info("[ir] 重试后波次状态: {}", fin.waves().stream()
                .map(w -> "wave" + w.waveIndex() + "=" + w.status()).toList());
        assertNoWaveInProgressAfterRollback(fin, 2, "ir");
        assertWaveAccountingInvariant(fin, "ir");

        // wave0：d0,d1 恢复、d2 人工；wave1：d3 人工、d4,d5 跳过；wave2：两台跳过
        assertEquals(2, fin.waves().get(0).rolledBackCount());
        assertEquals(1, fin.waves().get(0).needsManualCount());
        assertEquals(1, fin.waves().get(1).needsManualCount());
        assertEquals(2, fin.waves().get(1).skippedCount());
        assertEquals(2, fin.waves().get(2).skippedCount());
        assertWaveStatsMatchOutcome(fin, outcome, "ir");
        assertEquals(2, restoreCalls.get(), "重试不得重复恢复已处理设备");

        // 重复回滚：复现同一结果，波次状态与计数保持稳定
        RollbackOutcome again = orch.rollback("ir");
        BatchSnapshot fin2 = orch.snapshot("ir");
        assertNoWaveInProgressAfterRollback(fin2, 2, "ir");
        assertWaveStatsMatchOutcome(fin2, again, "ir");
        assertEquals(2, restoreCalls.get(), "重复回滚不得再次触发恢复");
        log.info("=== 用例通过：中断重试后各波次状态与统计收敛，重复回滚结果稳定 ===");
    }

    @Test
    void concurrentQueriesAndRepeatedRollbackStayConsistent() throws Exception {
        log.info("=== 用例：回滚过程中/之后并发查询与重复回滚，波次状态与计数稳定一致 ===");
        // 恢复 I/O 稍加延时，制造“回滚过程中”的并发查询窗口
        DeviceRestoreOperator slow = (device, target) -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            device.setCurrentVersion(target);
            return true;
        };
        RolloutOrchestrator orch = newSystem(new RollbackExecutor(slow));
        orch.createBatch("cc", "fw", all(), 20, waves());
        orch.start("cc");
        for (String d : List.of("d0", "d1", "d2")) {
            orch.report(ev("cc", d, ProgressPhase.SUCCEEDED));
        }
        orch.advanceWave("cc");
        orch.report(ev("cc", "d3", ProgressPhase.SUCCEEDED));
        orch.report(ev("cc", "d4", ProgressPhase.FAILED)); // 3 成 1 败 = 25% >= 20% 熔断

        // 熔断已触发（上报线程内同步执行完回滚）；再人工发起一次回滚 + 并发查询/重复回滚
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        if (tid % 2 == 0) {
                            // 并发重复回滚：结果必须与整批处置一致
                            RollbackOutcome o = orch.rollback("cc");
                            if (o.rolledBackSucceeded() != 4 || o.skippedNotStarted() != 3
                                    || o.failedDevices() != 1) {
                                violations.add("thread" + tid + " 重复回滚结果漂移: " + o);
                            }
                        } else {
                            // 并发查询：任何时刻都不允许有波次处于 ACTIVE，且计数自洽
                            BatchSnapshot s = orch.snapshot("cc");
                            for (WaveSnapshot w : s.waves()) {
                                if (w.status() == WaveStatus.ACTIVE) {
                                    violations.add("thread" + tid + " 观察到 wave "
                                            + w.waveIndex() + " 仍为 ACTIVE");
                                }
                                int sum = w.succeededCount() + w.failedCount()
                                        + w.inProgressCount() + w.notStartedCount()
                                        + w.rolledBackCount() + w.skippedCount()
                                        + w.needsManualCount();
                                if (sum != w.totalDevices()) {
                                    violations.add("thread" + tid + " wave " + w.waveIndex()
                                            + " 计数之和 " + sum + " != " + w.totalDevices());
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertTrue(violations.isEmpty(), "并发观察到的违例: " + violations);
        log.info("[cc] 并发查询/重复回滚 300 次无违例");

        // 收敛后的最终状态再整体核对一次
        BatchSnapshot snap = orch.snapshot("cc");
        assertNoWaveInProgressAfterRollback(snap, 2, "cc");
        assertWaveStatsMatchOutcome(snap, orch.rollback("cc"), "cc");
        log.info("=== 用例通过：并发查询与重复回滚下波次状态与计数稳定一致 ===");
    }
}
