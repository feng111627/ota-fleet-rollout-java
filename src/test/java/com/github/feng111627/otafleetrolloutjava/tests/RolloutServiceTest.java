package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.api.CreateBatchRequest;
import com.github.feng111627.otafleetrolloutjava.api.RegisterDeviceRequest;
import com.github.feng111627.otafleetrolloutjava.api.RegisterFirmwareRequest;
import com.github.feng111627.otafleetrolloutjava.api.ReportRequest;
import com.github.feng111627.otafleetrolloutjava.model.BatchStatus;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeStatus;
import com.github.feng111627.otafleetrolloutjava.model.RejectReason;
import com.github.feng111627.otafleetrolloutjava.model.RolloutBatch;
import com.github.feng111627.otafleetrolloutjava.model.RolloutCreateResult;
import com.github.feng111627.otafleetrolloutjava.repository.DeviceRepository;
import com.github.feng111627.otafleetrolloutjava.repository.FirmwareRepository;
import com.github.feng111627.otafleetrolloutjava.repository.RolloutRepository;
import com.github.feng111627.otafleetrolloutjava.service.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.service.DeviceService;
import com.github.feng111627.otafleetrolloutjava.service.FirmwareService;
import com.github.feng111627.otafleetrolloutjava.service.RolloutService;
import com.github.feng111627.otafleetrolloutjava.service.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 灰度编排测试：正常推进 / 重复上报 / 并发计数 / 兼容性拒绝 / 熔断回滚。 */
class RolloutServiceTest {
    private static final Logger log = LoggerFactory.getLogger(RolloutServiceTest.class);

    private DeviceService deviceService;
    private FirmwareService firmwareService;
    private RolloutService rolloutService;
    private RolloutRepository rolloutRepository;

    @BeforeEach
    void setUp() {
        DeviceRepository deviceRepository = new DeviceRepository();
        FirmwareRepository firmwareRepository = new FirmwareRepository();
        rolloutRepository = new RolloutRepository();
        CompatibilityService compatibilityService = new CompatibilityService();
        deviceService = new DeviceService(deviceRepository);
        firmwareService = new FirmwareService(firmwareRepository);
        rolloutService = new RolloutService(rolloutRepository, firmwareRepository,
                deviceRepository, compatibilityService);
    }

    private void registerFirmware(String fwId, String model, String minVersion, List<String> caps) {
        firmwareService.register(new RegisterFirmwareRequest(fwId, "2.0.0", model, minVersion, caps));
    }

    private void registerDevice(String id, String model, String version, List<String> caps) {
        deviceService.register(new RegisterDeviceRequest(id, model, version, caps));
    }

    private RolloutBatch createBatch(String fwId, List<String> deviceIds, List<Integer> stages,
                                     Double threshold, Integer minSamples) {
        CreateBatchRequest request = new CreateBatchRequest(fwId, deviceIds, stages, threshold, minSamples);
        RolloutCreateResult result = rolloutService.createBatch(request);
        return rolloutService.get(result.batchId());
    }

    private DeviceUpgradeStatus status(RolloutBatch batch, String deviceId) {
        return batch.getDevices().get(deviceId).getStatus();
    }

    @Test
    void normalRollout_progressesByStages_andCompletes() {
        registerFirmware("fw", "model-a", "1.0.0", List.of());
        for (int i = 1; i <= 5; i++) {
            registerDevice("d" + i, "model-a", "1.0.0", List.of());
        }
        RolloutBatch batch = createBatch("fw", List.of("d1", "d2", "d3", "d4", "d5"),
                List.of(20, 60, 100), null, null);

        // 第一档 20%：ceil(5*0.2)=1 台被放开
        rolloutService.advance(batch.getId());
        log.info("[测试输入] 第一档 20% => d1 状态={}, 其余 PENDING", status(batch, "d1"));
        assertEquals(DeviceUpgradeStatus.IN_PROGRESS, status(batch, "d1"));
        assertEquals(DeviceUpgradeStatus.PENDING, status(batch, "d2"));

        // 暂停后不允许放开下一档，恢复后可以
        rolloutService.pause(batch.getId());
        DomainException paused = assertThrows(DomainException.class,
                () -> rolloutService.advance(batch.getId()));
        assertEquals(RejectReason.BATCH_NOT_ACTIVE, paused.getReason());
        rolloutService.resume(batch.getId());

        // 第二档 60%：ceil(5*0.6)=3 台
        rolloutService.advance(batch.getId());
        assertEquals(BatchStatus.IN_PROGRESS, batch.getStatus());
        assertEquals(DeviceUpgradeStatus.IN_PROGRESS, status(batch, "d2"));
        assertEquals(DeviceUpgradeStatus.IN_PROGRESS, status(batch, "d3"));
        assertEquals(DeviceUpgradeStatus.PENDING, status(batch, "d4"));

        // 第三档 100% 全部放开并上报成功 => COMPLETED
        rolloutService.advance(batch.getId());
        for (int i = 1; i <= 5; i++) {
            rolloutService.report(batch.getId(),
                    new ReportRequest("d" + i, "SUCCESS", 100, "升级完成"));
        }
        log.info("[测试输入] 5 台全部 SUCCESS => 批次状态={}, 成功计数={}",
                batch.getStatus(), batch.getSuccessCount());
        assertEquals(BatchStatus.COMPLETED, batch.getStatus());
        assertEquals(5, batch.getSuccessCount());
    }

    @Test
    void duplicateReports_areIdempotent_sameResultAsSingleReport() {
        registerFirmware("fw", "model-a", null, List.of());
        registerDevice("d1", "model-a", "1.0.0", List.of());
        RolloutBatch batch = createBatch("fw", List.of("d1"), List.of(100), null, null);
        rolloutService.advance(batch.getId());

        // 进度心跳可重复
        rolloutService.report(batch.getId(), new ReportRequest("d1", "PROGRESS", 30, null));
        rolloutService.report(batch.getId(), new ReportRequest("d1", "PROGRESS", 60, null));

        // SUCCESS 上报多次，必须与只上报一次完全一致
        for (int i = 0; i < 5; i++) {
            rolloutService.report(batch.getId(), new ReportRequest("d1", "SUCCESS", 100, "ok"));
        }
        log.info("[测试输入] 同一设备 SUCCESS 重复上报 5 次 => 成功计数={}, 设备状态={}",
                batch.getSuccessCount(), status(batch, "d1"));
        assertEquals(1, batch.getSuccessCount());
        assertEquals(DeviceUpgradeStatus.SUCCESS, status(batch, "d1"));

        // 终态后再上报冲突终态被拒绝，计数仍不被带偏
        DomainException ex = assertThrows(DomainException.class,
                () -> rolloutService.report(batch.getId(), new ReportRequest("d1", "FAILED", null, "late")));
        assertEquals(RejectReason.ILLEGAL_TRANSITION, ex.getReason());
        assertEquals(0, batch.getFailedCount());
    }

    @Test
    void concurrentReports_countsAreNotCorrupted() throws InterruptedException {
        registerFirmware("fw", "model-a", null, List.of());
        int n = 20;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add("dev-" + i);
            registerDevice("dev-" + i, "model-a", "1.0.0", List.of());
        }
        RolloutBatch batch = createBatch("fw", ids, List.of(100), 1.0d, Integer.MAX_VALUE);
        rolloutService.advance(batch.getId());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        // 每个设备被上报 SUCCESS 3 次，共 n*3 个并发任务
        CountDownLatch done = new CountDownLatch(n * 3);
        // 每个设备既被上报 SUCCESS 多次（乱序、并发），模拟多人/多端同时上报
        for (int i = 0; i < n; i++) {
            final String deviceId = "dev-" + i;
            for (int r = 0; r < 3; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        rolloutService.report(batch.getId(),
                                new ReportRequest(deviceId, "SUCCESS", 100, "ok"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发上报未在限时内完成");
        pool.shutdown();

        log.info("[测试输入] {} 台设备 x 3 次 SUCCESS 并发上报 => 成功计数={}, 期望={}",
                n, batch.getSuccessCount(), n);
        assertEquals(n, batch.getSuccessCount(), "并发重复上报后成功计数必须恰好等于设备数");
        assertEquals(0, batch.getFailedCount());
    }

    @Test
    void createBatch_rejectsIncompatibleDevices_withDistinctReasons() {
        registerFirmware("fw", "model-a", "1.0.0", List.of("secure-boot"));
        registerDevice("ok", "model-a", "1.2.0", List.of("secure-boot"));
        registerDevice("wrong-model", "model-b", "1.2.0", List.of("secure-boot"));
        registerDevice("low-version", "model-a", "0.9.0", List.of("secure-boot"));
        registerDevice("missing-cap", "model-a", "1.2.0", List.of());

        RolloutCreateResult result = rolloutService.createBatch(new CreateBatchRequest(
                "fw", List.of("ok", "wrong-model", "low-version", "missing-cap", "ghost"),
                List.of(100), null, null));

        log.info("[测试输入] 4 类设备 + 1 不存在设备建批次 => 准入=1, 拒绝={}",
                result.rejectedDevices().stream().map(r -> r.deviceId() + ":" + r.reason()).toList());

        assertEquals(1, rolloutService.get(result.batchId()).getDevices().size());
        var byDevice = new HashMap<String, RejectReason>();
        result.rejectedDevices().forEach(r -> byDevice.put(r.deviceId(), r.reason()));
        assertEquals(RejectReason.MODEL_MISMATCH, byDevice.get("wrong-model"));
        assertEquals(RejectReason.VERSION_TOO_LOW, byDevice.get("low-version"));
        assertEquals(RejectReason.DEPENDENCY_MISSING, byDevice.get("missing-cap"));
        assertEquals(RejectReason.DEVICE_NOT_FOUND, byDevice.get("ghost"));
    }

    @Test
    void circuitBreaker_autoRollsBack_andDistinguishesUpgradedFromNotStarted() {
        registerFirmware("fw", "model-a", null, List.of());
        for (int i = 1; i <= 5; i++) {
            registerDevice("d" + i, "model-a", "1.0.0", List.of());
        }
        // 阈值 0.5，最小样本 2：第 2 个失败即触发（失败率 1.0 > 0.5）
        RolloutBatch batch = createBatch("fw", List.of("d1", "d2", "d3", "d4", "d5"),
                List.of(100), 0.5d, 2);
        rolloutService.advance(batch.getId()); // 5 台全部 IN_PROGRESS

        rolloutService.report(batch.getId(), new ReportRequest("d1", "SUCCESS", 100, null));
        rolloutService.report(batch.getId(), new ReportRequest("d2", "FAILED", null, "校验失败"));
        rolloutService.report(batch.getId(), new ReportRequest("d3", "TIMEOUT", null, "超时"));

        log.info("[测试输入] d1 SUCCESS, d2 FAILED, d3 TIMEOUT 后 => 批次状态={}", batch.getStatus());
        assertEquals(BatchStatus.CIRCUIT_OPEN, batch.getStatus());
        // 已成功的 d1 必须被下发回滚；其余设备尚未报终态
        assertEquals(DeviceUpgradeStatus.ROLLING_BACK, status(batch, "d1"));
        // 本场景档位已全部放开，没有未开始设备，d4/d5 在途，保持 IN_PROGRESS 等待终态
        assertEquals(DeviceUpgradeStatus.IN_PROGRESS, status(batch, "d4"));
        assertEquals(DeviceUpgradeStatus.IN_PROGRESS, status(batch, "d5"));

        // 在途设备陆续失败/超时（中途中断），不会产生半推半退：失败即终态，不推回滚包
        rolloutService.report(batch.getId(), new ReportRequest("d4", "FAILED", null, null));
        rolloutService.report(batch.getId(), new ReportRequest("d5", "TIMEOUT", null, null));
        // 仍有成功设备未确认回滚，批次保持熔断中
        assertEquals(BatchStatus.CIRCUIT_OPEN, batch.getStatus());

        // d1 确认回滚完成后，回滚才结束：成功设备已退回，在途设备维持失败/超时终态
        rolloutService.report(batch.getId(), new ReportRequest("d1", "ROLLBACK_DONE", null, null));

        log.info("[测试输入] d1 ROLLBACK_DONE, d4 FAILED, d5 TIMEOUT => 批次状态={}, d1={}, d4={}, d5={}",
                batch.getStatus(), status(batch, "d1"), status(batch, "d4"), status(batch, "d5"));
        assertEquals(BatchStatus.ROLLED_BACK, batch.getStatus());
        assertEquals(DeviceUpgradeStatus.ROLLED_BACK, status(batch, "d1"));
        assertEquals(DeviceUpgradeStatus.FAILED, status(batch, "d4"));
        assertEquals(DeviceUpgradeStatus.TIMEOUT, status(batch, "d5"));
    }

    @Test
    void rollback_marksNotStartedDevices_andRollsBackSuccessfulOnes() {
        registerFirmware("fw", "model-a", null, List.of());
        for (int i = 1; i <= 4; i++) {
            registerDevice("d" + i, "model-a", "1.0.0", List.of());
        }
        // 第一档只放开 25% = ceil(4*0.25)=1 台
        RolloutBatch batch = createBatch("fw", List.of("d1", "d2", "d3", "d4"),
                List.of(25, 100), null, null);
        rolloutService.advance(batch.getId());
        rolloutService.report(batch.getId(), new ReportRequest("d1", "SUCCESS", 100, null));

        // 手动回滚：d1 已成功 -> ROLLING_BACK；d2~d4 未开始 -> NOT_UPGRADED
        rolloutService.rollback(batch.getId());
        log.info("[测试输入] 手动回滚：d1 已成功, d2~d4 未开始 => d1={}, d2={}",
                status(batch, "d1"), status(batch, "d2"));
        assertEquals(BatchStatus.CIRCUIT_OPEN, batch.getStatus());
        assertEquals(DeviceUpgradeStatus.ROLLING_BACK, status(batch, "d1"));
        assertEquals(DeviceUpgradeStatus.NOT_UPGRADED, status(batch, "d2"));
        assertEquals(DeviceUpgradeStatus.NOT_UPGRADED, status(batch, "d4"));

        // 重复手动回滚幂等，不产生重复下发
        rolloutService.rollback(batch.getId());
        assertEquals(DeviceUpgradeStatus.ROLLING_BACK, status(batch, "d1"));

        rolloutService.report(batch.getId(), new ReportRequest("d1", "ROLLBACK_DONE", null, null));
        assertEquals(BatchStatus.ROLLED_BACK, batch.getStatus());
        assertEquals(DeviceUpgradeStatus.ROLLED_BACK, status(batch, "d1"));
    }
}
