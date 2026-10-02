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
import com.github.feng111627.otafleetrolloutjava.model.WaveProgressOutcome;
import com.github.feng111627.otafleetrolloutjava.model.WaveSnapshot;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 波次场景下的并发与重复触发幂等：
 * 多线程同时上报、推进动作被重复/并发调用时，同一波次只推进一次、不跳波不漏波，
 * 各波成功/失败计数不被带偏，最终状态唯一确定。
 */
class WaveConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(WaveConcurrencyTest.class);

    private RolloutOrchestrator orch;
    private DeviceRegistry devices;

    private static final int DEVICES = 60;
    private static final int WAVES = 5; // 60/5 => 每波 12 台

    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        FirmwareRegistry firmwares = new FirmwareRegistry();
        orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(), new RollbackExecutor());
        orch.registerFirmware(new FirmwarePackage("fw1", "M1", "2.0.0", "1.0.0", Map.of()));
        for (int i = 1; i <= DEVICES; i++) {
            orch.registerDevice(new Device("d" + i, "M1", "1.0.0", Map.of()));
        }
    }

    private List<String> deviceIds() {
        List<String> ids = new ArrayList<>(DEVICES);
        for (int i = 1; i <= DEVICES; i++) {
            ids.add("d" + i);
        }
        return ids;
    }

    @Test
    void concurrentReportsAndRepeatedAdvanceKeepEveryWaveExact() throws Exception {
        orch.createBatchWithWaveCount("cw", "fw1", deviceIds(), 100, WAVES);
        orch.start("cw");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        // 逐波推进：每一波由多线程并发重复上报（每台 SUCCEEDED 重复 8 次）+ 多线程并发
        // 重复调用 advanceWave。验证同一波只放开一次、计数精确。
        for (int waveNo = 0; waveNo < WAVES; waveNo++) {
            WaveSnapshot wave = orch.waveSnapshots("cw").get(waveNo);
            List<String> members = wave.deviceIds();
            CountDownLatch start = new CountDownLatch(1);

            // 上报任务：每台设备 8 个重复 SUCCEEDED + 2 个心跳
            List<Callable<Void>> reportTasks = new ArrayList<>();
            for (String d : members) {
                for (int r = 0; r < 8; r++) {
                    reportTasks.add(() -> {
                        start.await();
                        orch.report(new ProgressEvent("cw", d, ProgressPhase.SUCCEEDED, null, null));
                        return null;
                    });
                }
                for (int h = 0; h < 2; h++) {
                    reportTasks.add(() -> {
                        start.await();
                        orch.report(ProgressEvent.heartbeat("cw", d));
                        return null;
                    });
                }
            }
            // 推进任务：当前波未全部终态时应原地不动；全部终态后只有一个调用真正推进。
            // 启动 8 个推进者持续尝试，直到有一个报告“波次已前进/批次完成”。
            AtomicInteger advancedCount = new AtomicInteger();
            List<Callable<Void>> advancers = new ArrayList<>();
            for (int a = 0; a < 8; a++) {
                advancers.add(() -> {
                    start.await();
                    for (int attempt = 0; attempt < 200; attempt++) {
                        WaveProgressOutcome out = orch.advanceWave("cw");
                        if (out.advanced()) {
                            advancedCount.incrementAndGet();
                            return null;
                        }
                        if (out.completed()) {
                            return null;
                        }
                        Thread.yield();
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> t : reportTasks) {
                futures.add(pool.submit(t));
            }
            for (Callable<Void> t : advancers) {
                futures.add(pool.submit(t));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get();
            }

            WaveSnapshot after = orch.waveSnapshots("cw").get(waveNo);
            log.info("[cw] wave#{} closed: succeeded={} total={} advancedCallers={}",
                    waveNo, after.succeeded(), after.total(), advancedCount.get());
            assertEquals(members.size(), after.succeeded(),
                    "第 " + waveNo + " 波成功计数必须恰好等于本波设备数");
            assertEquals(0, after.failed());
            // 放开下一波的真正推进者只能有一个（最后一波时 advanced 由收口调用承担）
            assertEquals(1, advancedCount.get(),
                    "同一波次只能被真正推进一次");
        }
        pool.shutdown();

        BatchSnapshot snapshot = orch.snapshot("cw");
        log.info("[cw] final status={} totalSucceeded={} currentWave={}",
                snapshot.status(), snapshot.succeededCount(), snapshot.currentWaveNo());
        assertEquals(BatchStatus.COMPLETED, snapshot.status());
        assertEquals(DEVICES, snapshot.succeededCount());
        assertEquals(WAVES - 1, snapshot.currentWaveNo());

        // 各波统计独立正确，总和等于整批
        int sum = 0;
        for (WaveSnapshot w : orch.waveSnapshots("cw")) {
            assertEquals(DEVICES / WAVES, w.succeeded());
            assertEquals(0, w.failed());
            sum += w.succeeded();
            for (String d : w.deviceIds()) {
                assertEquals("2.0.0", devices.get(d).getCurrentVersion());
                assertEquals(DeviceUpgradeState.SUCCEEDED, snapshot.entries().stream()
                        .filter(e -> e.getDeviceId().equals(d)).findFirst().orElseThrow().getState());
            }
        }
        assertEquals(DEVICES, sum);
    }

    @Test
    void repeatedAdvanceAfterCompletionIsIdempotent() {
        orch.createBatchWithWaveCount("cw2", "fw1", List.of("d1", "d2", "d3", "d4"), 100, 2);
        orch.start("cw2");
        for (String d : List.of("d1", "d2")) {
            orch.report(new ProgressEvent("cw2", d, ProgressPhase.SUCCEEDED, null, null));
        }
        assertTrue(orch.advanceWave("cw2").advanced());
        for (String d : List.of("d3", "d4")) {
            orch.report(new ProgressEvent("cw2", d, ProgressPhase.SUCCEEDED, null, null));
        }
        WaveProgressOutcome last = orch.advanceWave("cw2");
        assertTrue(last.advanced());
        assertTrue(last.completed());

        // 完成后重复推进：状态与只推进一次完全一致，advanced=false、不抛异常
        for (int i = 0; i < 5; i++) {
            WaveProgressOutcome again = orch.advanceWave("cw2");
            assertFalse(again.advanced());
            assertTrue(again.completed());
            assertEquals(1, again.currentWaveNo());
            assertEquals(BatchStatus.COMPLETED, again.batchStatus());
        }
        // 计数不被带偏
        assertEquals(4, orch.snapshot("cw2").succeededCount());
        log.info("[cw2] repeated advance after completion stays idempotent, succeeded=4");
    }
}
