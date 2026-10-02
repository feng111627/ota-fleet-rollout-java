package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.exception.BatchStateException;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迟到/重复上报在关键时序点上的幂等一致性：
 * <ol>
 *   <li>波次刚切换：上一波设备用相同/不同 eventId 重复上报，结果与只报一次一致；</li>
 *   <li>设备在“当前波未上报、波次却已被推进”的极端情况下补报，不产生异常升级；</li>
 *   <li>批次熔断回滚之后：重复终态上报幂等，全新上报被明确拒绝，计数/版本不变；</li>
 *   <li>同一上报在推进前后各发一次，最终计数恰好一次。</li>
 * </ol>
 */
class WaveLateReportTest {

    private static final Logger log = LoggerFactory.getLogger(WaveLateReportTest.class);

    private RolloutOrchestrator orch;

    @BeforeEach
    void setUp() {
        DeviceRegistry devices = new DeviceRegistry();
        FirmwareRegistry firmwares = new FirmwareRegistry();
        orch = new RolloutOrchestrator(
                devices, firmwares, new CompatibilityService(), new RollbackExecutor());
        orch.registerFirmware(new FirmwarePackage("fw1", "M1", "2.0.0", "1.0.0", Map.of()));
        for (int i = 1; i <= 4; i++) {
            orch.registerDevice(new Device("d" + i, "M1", "1.0.0", Map.of()));
        }
        orch.createBatchWithWaveSizes("late", "fw1",
                List.of("d1", "d2", "d3", "d4"), 100, List.of(2, 2));
        orch.start("late");
    }

    private ProgressReportResult report(String device, ProgressPhase phase, String msg, String eid) {
        return orch.report(new ProgressEvent("late", device, phase, msg, eid));
    }

    @Test
    void duplicateAndLateReportsAroundWaveSwitchAreStable() {
        // 第一波两台设备，分别用“默认键”和“显式 eventId”成功
        assertTrue(report("d1", ProgressPhase.SUCCEEDED, null, null).accepted());
        assertTrue(report("d1", ProgressPhase.SUCCEEDED, null, null).duplicate(),
                "推进前同键重复已被识别");
        assertTrue(report("d2", ProgressPhase.SUCCEEDED, null, "evt-d2-ok").accepted());

        // 推进到第二波
        assertTrue(orch.advanceWave("late").advanced());
        log.info("[late] wave switched to #1; replaying wave#0 reports");

        // 节骨眼 1：上一波设备“完全相同”的成功上报在推进后重放 => duplicate，不多算
        ProgressReportResult d1Again = report("d1", ProgressPhase.SUCCEEDED, null, null);
        assertTrue(d1Again.duplicate());
        assertFalse(d1Again.accepted());
        assertEquals(DeviceUpgradeState.SUCCEEDED, d1Again.deviceState());

        // 显式 eventId 的重复也一样
        ProgressReportResult d2Again = report("d2", ProgressPhase.SUCCEEDED, null, "evt-d2-ok");
        assertTrue(d2Again.duplicate());

        // 节骨眼 2：上一波设备用“不同 eventId”再报成功 => 终态不可翻转，安全拒绝、不计数
        ProgressReportResult d1Other = report("d1", ProgressPhase.SUCCEEDED, null, "evt-d1-other");
        assertFalse(d1Other.accepted());
        assertFalse(d1Other.duplicate());
        assertEquals(DeviceUpgradeState.SUCCEEDED, d1Other.deviceState());

        // 计数：第一波仍恰好 2 成功
        assertEquals(2, orch.snapshot("late").succeededCount());

        // 节骨眼 3：当前波设备 d3 在它自己波次进行中的重复成功（发两次，一次生效）
        assertTrue(report("d3", ProgressPhase.SUCCEEDED, null, "evt-d3").accepted());
        assertTrue(report("d3", ProgressPhase.SUCCEEDED, null, "evt-d3").duplicate());
        assertEquals(3, orch.snapshot("late").succeededCount());
    }

    @Test
    void reportsAfterRollbackAreIdempotentOrClearlyRejected() {
        // 阈值 100 不会因失败熔断，直接人工回滚，制造“回滚后补报”场景
        report("d1", ProgressPhase.SUCCEEDED, null, "evt-d1");
        report("d2", ProgressPhase.DOWNLOADING, null, null); // IN_PROGRESS
        var rolledBack = orch.rollback("late");
        assertEquals(BatchStatus.ROLLED_BACK, rolledBack.finalStatus());
        log.info("[late] batch rolled back; replaying reports after rollback");

        // d1 回滚成功后，用相同 eventId 重复成功上报 => 幂等 duplicate
        ProgressReportResult dup = report("d1", ProgressPhase.SUCCEEDED, null, "evt-d1");
        assertTrue(dup.duplicate(), "回滚后的同键重复上报必须幂等");
        assertFalse(dup.accepted());
        assertEquals(DeviceUpgradeState.ROLLBACK_SUCCEEDED, dup.deviceState());

        // 回滚后“从未上报过”的后波设备 d3 的全新上报 => 明确拒绝，不产生异常升级
        BatchStateException rejected = assertThrows(BatchStateException.class,
                () -> report("d3", ProgressPhase.SUCCEEDED, null, "evt-d3-late"));
        assertEquals("BATCH_ROLLED_BACK", rejected.getReasonCode());

        // IN_PROGRESS 被标人工的 d2，回滚后重复 DOWNLOADING 不允许改变状态
        BatchStateException d2 = assertThrows(BatchStateException.class,
                () -> report("d2", ProgressPhase.INSTALLING, null, "late-d2"));
        assertEquals("BATCH_ROLLED_BACK", d2.getReasonCode());

        // 回滚后不允许再推进
        assertFalse(orch.advanceWave("late").advanced());
        assertEquals(BatchStatus.ROLLED_BACK, orch.snapshot("late").status());
        // 计数与版本不被迟到上报改动
        assertEquals(1, orch.snapshot("late").succeededCount());
        log.info("[late] post-rollback replays caused no count/version drift");
    }
}
