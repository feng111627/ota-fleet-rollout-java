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
 * “批次切到 CIRCUIT_OPEN”在同一把批次锁内完成，因此执行期间不会有新上报混入，
 * <b>后面还没开始的波次原地不动、不会被悄悄放开</b>。
 *
 * <p>处置时区分三类设备（与波次无关，逐台判定）：
 * <ul>
 *   <li><b>已升级成功（SUCCEEDED）</b>：设备已运行新固件，必须远程恢复原版本；
 *       恢复成功记 ROLLBACK_SUCCEEDED，恢复失败（报错/超时）记 ROLLBACK_FAILED；</li>
 *   <li><b>尚未开始（NOT_STARTED）</b>：无需任何动作，记 ROLLBACK_SKIPPED
 *       （后续波次的设备全部落在这里，按波次分别汇总）；</li>
 *   <li><b>其余（IN_PROGRESS/FAILED 等）</b>：固件状态不确定，禁止盲目远程操作，
 *       记 ROLLBACK_FAILED 待人工介入。</li>
 * </ul>
 *
 * <p><b>中断可续跑</b>：每台设备先在锁外执行恢复 I/O，再在批次锁内提交最终状态。
 * 若整体在中途报错/中断（批次仍是 CIRCUIT_OPEN），重试时 beginRollback 重新采集快照，
 * 已是回滚终态（ROLLBACK_*）的设备直接跳过、绝不重复恢复，未处理设备继续处置，
 * 最后 {@link Batch#completeRollback()} 收尾，不会留下半推半退的状态。
 * 结果通过 {@link RollbackOutcomeFactory} 同时给出整批计数与按波次分别的处置统计。
 */
public class RollbackExecutor {

    private final DeviceRestoreOperator restoreOperator;

    public RollbackExecutor() {
        this(new LocalRestoreOperator());
    }

    public RollbackExecutor(DeviceRestoreOperator restoreOperator) {
        this.restoreOperator = restoreOperator;
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

        for (RollbackCaptureItem item : capture.items()) {
            BatchDeviceEntry entry = item.entry();
            DeviceUpgradeState before = item.beforeState();
            DeviceUpgradeState outcome;

            // 重试幂等：已是回滚终态的设备不再重复处置（中断后续跑的关键）
            if (before == DeviceUpgradeState.ROLLBACK_SUCCEEDED
                    || before == DeviceUpgradeState.ROLLBACK_SKIPPED
                    || before == DeviceUpgradeState.ROLLBACK_FAILED) {
                outcome = before;
            } else if (before == DeviceUpgradeState.NOT_STARTED) {
                // 还没开始升级：设备仍在原版本，无需回滚（后续波次设备全部走这里）
                outcome = DeviceUpgradeState.ROLLBACK_SKIPPED;
            } else if (before == DeviceUpgradeState.SUCCEEDED) {
                // 已升级成功：把版本恢复到建批时快照的原版本
                Device device = deviceRegistry.get(entry.getDeviceId());
                boolean ok;
                try {
                    ok = restoreOperator.restore(device, item.originalVersion());
                } catch (RuntimeException e) {
                    // 恢复过程报错/中断：不写回“假成功”，标记人工介入
                    ok = false;
                }
                outcome = ok ? DeviceUpgradeState.ROLLBACK_SUCCEEDED : DeviceUpgradeState.ROLLBACK_FAILED;
            } else {
                // IN_PROGRESS / FAILED：刷写中途或已失败，固件状态不确定，禁止盲目操作
                outcome = DeviceUpgradeState.ROLLBACK_FAILED;
            }

            // 锁内提交最终状态，保证与批次计数/状态观察一致
            synchronized (batch) {
                entry.setPreRollbackState(before);
                entry.setState(outcome);
            }
            results.add(new RollbackDeviceResult(
                    entry.getDeviceId(), item.originalVersion(), before, outcome,
                    entry.getWaveIndex()));

            // 单台提交后的回调（默认空实现）；测试可借此在“处理到一半”时抛出异常模拟中断
            afterDeviceCommitted(batch, entry, outcome);
        }

        batch.completeRollback();
        return RollbackOutcomeFactory.build(batch, results);
    }

    /**
     * 每台设备处置并在批次锁内提交后的扩展点：默认什么都不做。
     * 抛出异常会让本次回滚停在当前位置（批次保持 CIRCUIT_OPEN），重试可继续。
     */
    protected void afterDeviceCommitted(Batch batch, BatchDeviceEntry entry,
                                        DeviceUpgradeState outcome) {
        // no-op
    }
}
