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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 波次推进与上报的并发/幂等测试：
 * <ul>
 *   <li>同一波次被多线程同时推进：只放开一次，不跳波不漏波；重复推进拿到与只推进一次相同的结果；</li>
 *   <li>多线程并发上报：每台设备成功重复 12 次并混用心跳，每个波次计数精确，批次最终 COMPLETED；</li>
 *   <li>严格分阶段（先并发上报、再并发推进），符合“一波走完经确认才放开下一波”的语义。</li>
 * </ul>
 */
class WaveConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(WaveConcurrencyTest.class);

    private static final int WAVE_COUNT = 5;
    private static final int DEVICES_PER_WAVE = 20; // 共 100 台
    private static final int TOTAL = WAVE_COUNT * DEVICES_PER_WAVE;

    private RolloutOrchestrator orch;
    private DeviceRegistry devices;

    @BeforeEach
    void setUp() {
        devices = new DeviceRegistry();
        FirmwareRegistry firmwares = new FirmwareRegistry();
        orch = new RolloutOrchestrator(devices, firmwares,
                new CompatibilityService(), new RollbackExecutor());
        orch.registerFirmware(new FirmwarePackage("fw", "M", "3.0.0", "1.0.0", Map.of()));
        for (int i = 0; i < TOTAL; i++) {
            orch.registerDevice(new Device("dev-" + i, "M", "1.0.0", Map.of()));
        }
    }

    private List<List<String>> buildWaves() {
        List<List<String>> waves = new ArrayList<>();
        for (int w = 0; w < WAVE_COUNT; w++) {
            List<String> wave = new ArrayList<>();
            for (int i = 0; i < DEVICES_PER_WAVE; i++) {
                wave.add("dev-" + (w * DEVICES_PER_WAVE + i));
            }
            waves.add(List.copyOf(wave));
        }
        return List.copyOf(waves);
    }

    /** 让 N 个线程在同一起跑线同时调用一次 advanceWave，返回真正放开的次数 */
    private int burstAdvance(String batchId, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WaveAdvanceResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return orch.advanceWave(batchId);
            }));
        }
        ready.await();
        start.countDown();
        int advanced = 0;
        for (Future<WaveAdvanceResult> f : futures) {
            if (f.get().advanced()) {
                advanced++;
            }
        }
        pool.shutdown();
        return advanced;
    }

    @Test
    void concurrentAdvanceOpensNextWaveOnlyOnce() throws Exception {
        orch.createBatch("ca", "fw", devices.list().stream().map(Device::getDeviceId).toList(),
                100, buildWaves());
        orch.start("ca");
        for (String d : buildWaves().get(0)) {
            orch.report(new ProgressEvent("ca", d, ProgressPhase.SUCCEEDED, null, null));
        }

        int advanced = burstAdvance("ca", 24);

        BatchSnapshot snap = orch.snapshot("ca");
        log.info("[ca] concurrent advance: advancedCalls={}, activeWave={}, wave0={}, wave1={}",
                advanced, snap.activeWaveIndex(),
                snap.waves().get(0).status(), snap.waves().get(1).status());
        assertEquals(1, advanced, "同一波次只能被推进一次");
        assertEquals(1, snap.activeWaveIndex(), "不能跳波");
        assertEquals(WaveStatus.ACTIVE, snap.waves().get(1).status());
        assertEquals(0, snap.waves().get(1).succeededCount());
        assertEquals(DEVICES_PER_WAVE, snap.waves().get(1).notStartedCount());

        // 再爆发一次重复推进：第二波还没结束，必须一次都不放开
        int secondBurst = burstAdvance("ca", 24);
        assertEquals(0, secondBurst);
        WaveAdvanceResult probe = orch.advanceWave("ca");
        assertFalse(probe.advanced());
        assertEquals("WAVE_NOT_FINISHED", probe.reasonCode());
        assertEquals(1, orch.snapshot("ca").activeWaveIndex());
    }

    @Test
    void concurrentReportsAcrossWavesKeepExactPerWaveCounters() throws Exception {
        List<List<String>> waves = buildWaves();
        orch.createBatch("cc", "fw",
                devices.list().stream().map(Device::getDeviceId).toList(), 100, waves);
        orch.start("cc");

        int reportThreads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(reportThreads);
        AtomicInteger rejectedFromFutureWave = new AtomicInteger();

        for (int w = 0; w < WAVE_COUNT; w++) {
            List<String> waveDevices = waves.get(w);
            CountDownLatch ready = new CountDownLatch(reportThreads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger seq = new AtomicInteger();
            List<Callable<Void>> tasks = new ArrayList<>();

            // 每台设备 SUCCEEDED 重复 12 次 + 每波 100 个心跳，全部在起跑线后并发执行
            for (String d : waveDevices) {
                for (int repeat = 0; repeat < 12; repeat++) {
                    tasks.add(() -> {
                        try {
                            orch.report(new ProgressEvent("cc", d, ProgressPhase.SUCCEEDED, null, null));
                        } catch (BatchStateException e) {
                            // 理论上不会发生（推进严格分阶段），出现即失败
                            if ("WAVE_NOT_OPENED".equals(e.getReasonCode())) {
                                rejectedFromFutureWave.incrementAndGet();
                            } else {
                                throw e;
                            }
                        }
                        return null;
                    });
                }
            }
            for (int h = 0; h < 100; h++) {
                final String d = waveDevices.get(h % waveDevices.size());
                tasks.add(() -> {
                    orch.report(ProgressEvent.heartbeat("cc", d));
                    return null;
                });
            }

            // 24 个 worker 共同执行 340 个任务，用 AtomicInteger 取任务序号，起跑线一致
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < reportThreads; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    int i;
                    while ((i = seq.getAndIncrement()) < tasks.size()) {
                        tasks.get(i).call();
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }

            // 上报阶段结束：本波统计必须精确
            BatchSnapshot mid = orch.snapshot("cc");
            WaveSnapshot ws = mid.waves().get(w);
            log.info("[cc] wave {} reported: status={}, succ={}, failed={}, activeWave={}",
                    w, ws.status(), ws.succeededCount(), ws.failedCount(), mid.activeWaveIndex());
            assertEquals(DEVICES_PER_WAVE, ws.succeededCount(),
                    "wave " + w + " 成功计数必须恰好等于该波设备数");
            assertEquals(0, ws.failedCount());

            // 推进阶段：前 4 波用 24 线程同时推进，恰好放开一次；最后一波批次直接 COMPLETED
            if (w < WAVE_COUNT - 1) {
                int opened = burstAdvance("cc", 24);
                assertEquals(1, opened, "wave " + w + " -> " + (w + 1) + " 必须恰好放开一次");
                assertEquals(w + 1, orch.snapshot("cc").activeWaveIndex());
                assertEquals(WaveStatus.ACTIVE, orch.snapshot("cc").waves().get(w + 1).status());
            }
        }
        pool.shutdown();

        BatchSnapshot fin = orch.snapshot("cc");
        log.info("[cc] FINAL status={} totalSucc={} totalFailed={}, futureWaveRejections={}",
                fin.status(), fin.succeededCount(), fin.failedCount(), rejectedFromFutureWave.get());
        assertEquals(BatchStatus.COMPLETED, fin.status());
        assertEquals(TOTAL, fin.succeededCount());
        assertEquals(0, fin.failedCount());
        assertEquals(0, rejectedFromFutureWave.get());
        for (WaveSnapshot ws : fin.waves()) {
            assertEquals(DEVICES_PER_WAVE, ws.succeededCount());
            assertEquals(0, ws.notStartedCount());
            assertEquals(0, ws.inProgressCount());
        }
        List<String> wrongVersion = fin.entries().stream()
                .filter(e -> !"3.0.0".equals(devices.get(e.getDeviceId()).getCurrentVersion()))
                .map(e -> e.getDeviceId()).collect(Collectors.toList());
        assertEquals(List.of(), wrongVersion);
        List<DeviceUpgradeState> distinct = fin.entries().stream()
                .map(e -> e.getState()).distinct().collect(Collectors.toList());
        assertEquals(List.of(DeviceUpgradeState.SUCCEEDED), distinct);
    }
}
