package com.github.feng111627.otafleetrolloutjava.rollout;

import com.github.feng111627.otafleetrolloutjava.model.Batch;
import com.github.feng111627.otafleetrolloutjava.model.BatchDeviceEntry;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState;
import com.github.feng111627.otafleetrolloutjava.model.RollbackCapture;
import com.github.feng111627.otafleetrolloutjava.model.RollbackCaptureItem;
import com.github.feng111627.otafleetrolloutjava.model.RollbackDeviceResult;
import com.github.feng111627.otafleetrolloutjava.model.RollbackOutcome;
import com.github.feng111627.otafleetrolloutjava.registry.DeviceRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 批次回滚执行器。
 *
 * <p>回滚在 {@link Batch#beginRollback()} 采集的一致性快照上执行，采集与
 * “批次切到 CIRCUIT_OPEN”在同一把批次锁内完成，因此执行期间不会有新上报混入。
 *
 * <p>处置时区分三类设备：
 * <ul>
 *   <li><b>已升级成功（SUCCEEDED）</b>：设备已运行新固件，必须远程恢复原版本；
 *       恢复成功记 ROLLBACK_SUCCEEDED，恢复失败（报错/超时）记 ROLLBACK_FAILED；</li>
 *   <li><b>尚未开始（NOT_STARTED）</b>：设备仍在原版本，无需任何动作，记 ROLLBACK_SKIPPED；</li>
 *   <li><b>其余（IN_PROGRESS/FAILED 等）</b>：可能处于刷写中途或已失败，
 *       本地无法确认其固件状态，不做可能造成损坏的操作，记 ROLLBACK_FAILED 待人工介入。</li>
 * </ul>
 *
 * <p>每台设备先在锁外执行恢复 I/O，再进入批次锁写回最终状态，中途若整体报错或中断，
 * 已完成的设备保持已回滚、未处理的设备保持快照原状（批次仍为 CIRCUIT_OPEN），
 * 重试时 beginRollback 会重新采集并跳过已是回滚终态的设备，不会留下半推半退状态。
 */
public class RollbackExecutor {

    private final DeviceRestoreOperator restoreOperator;
    private final RollbackAbortTrigger abortTrigger;

    public RollbackExecutor() {
        this(new LocalRestoreOperator());
    }

    public RollbackExecutor(DeviceRestoreOperator restoreOperator) {
        this(restoreOperator, RollbackAbortTrigger.NOOP);
    }

    public RollbackExecutor(DeviceRestoreOperator restoreOperator, RollbackAbortTrigger abortTrigger) {
        this.restoreOperator = restoreOperator;
        this.abortTrigger = abortTrigger == null ? RollbackAbortTrigger.NOOP : abortTrigger;
    }

    /**
     * 执行回滚。必须在 beginRollback 返回非空快照后调用。
     */
    public RollbackOutcome execute(Batch batch, DeviceRegistry deviceRegistry) {
        return execute(batch, deviceRegistry, batch.beginRollback());
    }

    /**
     * 执行回滚（显式传入已采集的快照）。
     */
    public RollbackOutcome execute(Batch batch, DeviceRegistry deviceRegistry, RollbackCapture capture) {
        List<RollbackDeviceResult> results = new ArrayList<>(capture.items().size());
        int rolledBackSucceeded = 0;
        int skippedNotStarted = 0;
        int failedDevices = 0;

        for (RollbackCaptureItem item : capture.items()) {
            BatchDeviceEntry entry = item.entry();
            DeviceUpgradeState before = item.beforeState();
            DeviceUpgradeState outcome;

            // 重试幂等：已是回滚终态的设备不再重复处置，也不再触发中止钩子
            if (before == DeviceUpgradeState.ROLLBACK_SUCCEEDED
                    || before == DeviceUpgradeState.ROLLBACK_SKIPPED
                    || before == DeviceUpgradeState.ROLLBACK_FAILED) {
                outcome = before;
            } else {
                // 模拟“回滚整体中途报错/被打断”：钩子在处置未处理设备前抛出时，本轮立即
                // 中断，已提交终态的设备保持现状、批次保持 CIRCUIT_OPEN（不会走到 completeRollback）。
                abortTrigger.check(batch.getBatchId(), entry.getDeviceId(), before);
                if (before == DeviceUpgradeState.NOT_STARTED) {
                    // 还没开始升级：设备仍在原版本，无需回滚
                    outcome = DeviceUpgradeState.ROLLBACK_SKIPPED;
                } else if (before == DeviceUpgradeState.SUCCEEDED) {
                    // 已升级成功：必须把版本恢复到建批时快照的原版本
                    Device device = deviceRegistry.get(entry.getDeviceId());
                    boolean ok;
                    try {
                        ok = restoreOperator.restore(device, item.originalVersion());
                    } catch (RuntimeException e) {
                        // 恢复过程报错/中断：不写回一个“假成功”，标记人工介入
                        ok = false;
                    }
                    outcome = ok ? DeviceUpgradeState.ROLLBACK_SUCCEEDED : DeviceUpgradeState.ROLLBACK_FAILED;
                } else {
                    // IN_PROGRESS / FAILED：刷写中途或已失败，固件状态不确定，禁止盲目操作
                    outcome = DeviceUpgradeState.ROLLBACK_FAILED;
                }
            }

            // 锁内提交最终状态，保证与批次计数/状态观察一致
            synchronized (batch) {
                entry.setState(outcome);
            }

            switch (outcome) {
                case ROLLBACK_SUCCEEDED -> rolledBackSucceeded++;
                case ROLLBACK_SKIPPED -> skippedNotStarted++;
                default -> failedDevices++;
            }
            results.add(new RollbackDeviceResult(
                    entry.getDeviceId(), item.originalVersion(), before, outcome));
        }

        batch.completeRollback();
        RollbackOutcome outcome = new RollbackOutcome(batch.getBatchId(), batch.getStatus(),
                rolledBackSucceeded, skippedNotStarted, failedDevices, results);
        return outcome.withWaveResults(RollbackWaveGrouper.group(batch, results));
    }
}
