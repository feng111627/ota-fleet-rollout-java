package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复上报幂等 + 多线程并发计数测试。
 */
class IdempotentAndConcurrentReportTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAndConcurrentReportTest.class);

    private RolloutOrchestrator orchestrator;
    private DeviceRegistry devices;

    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        FirmwareRegistry firmwares = new FirmwareRegistry();
        orchestrator = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(), new RollbackExecutor());
        orchestrator.registerFirmware(new FirmwarePackage(
                "fw1", "M1", "2.0.0", "1.0.0", Map.of()));
        for (int i = 1; i <= 20; i++) {
            orchestrator.registerDevice(new Device("d" + i, "M1", "1.0.0", Map.of()));
        }
    }

    @Test
    void duplicateSuccessReportsMustBeIdempotent() {
        orchestrator.createBatch("dup", "fw1", List.of("d1"), 50);
        orchestrator.start("dup");

        ProgressEvent first = new ProgressEvent("dup", "d1", ProgressPhase.SUCCEEDED, null, null);
        ProgressReportResult r1 = orchestrator.report(first);
        log.info("[dup] first report accepted={} state={}", r1.accepted(), r1.deviceState());
        assertTrue(r1.accepted());
        assertFalse(r1.duplicate());

        // 同一终态连续上报 5 次
        for (int i = 0; i < 5; i++) {
            ProgressReportResult rn = orchestrator.report(
                    new ProgressEvent("dup", "d1", ProgressPhase.SUCCEEDED, null, null));
            assertFalse(rn.accepted(), "重复上报不得再次生效");
            assertTrue(rn.duplicate(), "重复上报必须被标记 duplicate");
            assertEquals(DeviceUpgradeState.SUCCEEDED, rn.deviceState());
        }

        // 终态后再报 FAILED 不得翻转
        ProgressReportResult failed = orchestrator.report(
                new ProgressEvent("dup", "d1", ProgressPhase.FAILED, "late failure", null));
        assertFalse(failed.accepted());
        assertEquals(DeviceUpgradeState.SUCCEEDED, failed.deviceState());

        BatchSnapshot snapshot = orchestrator.snapshot("dup");
        log.info("[dup] final succeeded={} failed={} status={}",
                snapshot.succeededCount(), snapshot.failedCount(), snapshot.status());
        assertEquals(1, snapshot.succeededCount());
        assertEquals(0, snapshot.failedCount());
        assertEquals(BatchStatus.COMPLETED, snapshot.status());
        assertEquals("2.0.0", devices.get("d1").getCurrentVersion());
    }

    @Test
    void sameEventIdReportedTwiceIsOnlyAppliedOnce() {
        orchestrator.createBatch("dup2", "fw1", List.of("d1"), 50);
        orchestrator.start("dup2");

        ProgressEvent e1 = new ProgressEvent("dup2", "d1", ProgressPhase.SUCCEEDED, null, "evt-123");
        assertTrue(orchestrator.report(e1).accepted());
        // 即使 phase 默认去重键不同，相同 eventId 也视为同一次上报
        ProgressReportResult again = orchestrator.report(
                new ProgressEvent("dup2", "d1", ProgressPhase.SUCCEEDED, null, "evt-123"));
        log.info("[dup2] same eventId accepted={} duplicate={}", again.accepted(), again.duplicate());
        assertTrue(again.duplicate());
        assertEquals(1, orchestrator.snapshot("dup2").succeededCount());
    }

    @Test
    void concurrentReportsMustNotCorruptCounters() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            ids.add("d" + i);
        }
        // 阈值 100 + 全部成功 => 失败率永远为 0，不会触发熔断
        orchestrator.createBatch("conc", "fw1", ids, 100);
        orchestrator.start("conc");

        int threads = 16;
        int repeats = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new ArrayList<>();
        // 每台设备的 SUCCEEDED 被 10 个任务重复上报
        for (String d : ids) {
            for (int r = 0; r < repeats; r++) {
                tasks.add(() -> {
                    orchestrator.report(new ProgressEvent(
                            "conc", d, ProgressPhase.SUCCEEDED, null, null));
                    return null;
                });
            }
        }
        // 再混入大量心跳
        for (int r = 0; r < 200; r++) {
            String d = ids.get(r % ids.size());
            tasks.add(() -> {
                orchestrator.report(ProgressEvent.heartbeat("conc", d));
                return null;
            });
        }

        List<Future<Void>> futures = pool.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(); // 有异常会在此抛出
        }
        pool.shutdown();

        BatchSnapshot snapshot = orchestrator.snapshot("conc");
        log.info("[conc] totalReports={} succeededCount={} failedCount={} status={}",
                tasks.size(), snapshot.succeededCount(), snapshot.failedCount(), snapshot.status());

        assertEquals(20, snapshot.succeededCount(), "成功计数必须恰好等于设备数");
        assertEquals(0, snapshot.failedCount());
        assertEquals(BatchStatus.COMPLETED, snapshot.status());
        for (String d : ids) {
            assertEquals("2.0.0", devices.get(d).getCurrentVersion());
        }

        // 每个条目状态都必须是 SUCCEEDED
        List<DeviceUpgradeState> states = snapshot.entries().stream()
                .map(e -> e.getState()).distinct().collect(Collectors.toList());
        assertEquals(List.of(DeviceUpgradeState.SUCCEEDED), states);
    }
}
