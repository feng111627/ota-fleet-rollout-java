package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.model.Batch;
import com.github.feng111627.otafleetrolloutjava.model.BatchDeviceEntry;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState;
import com.github.feng111627.otafleetrolloutjava.rollout.DeviceRestoreOperator;
import com.github.feng111627.otafleetrolloutjava.rollout.RollbackExecutor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试专用回滚执行器：可在“处理完前 failAfter 台设备”时抛出异常模拟回滚中途被打断；
 * 关闭开关后同一实例可直接重试，用来验证中断续跑与幂等。
 */
class InterruptibleRollbackExecutor extends RollbackExecutor {

    private final AtomicInteger committed = new AtomicInteger();
    private volatile boolean interruptEnabled;
    private final int failAfter;

    InterruptibleRollbackExecutor(DeviceRestoreOperator restoreOperator, int failAfter) {
        super(restoreOperator);
        this.failAfter = failAfter;
        this.interruptEnabled = true;
    }

    void setInterruptEnabled(boolean enabled) {
        this.interruptEnabled = enabled;
    }

    @Override
    protected void afterDeviceCommitted(Batch batch, BatchDeviceEntry entry,
                                        DeviceUpgradeState outcome) {
        if (interruptEnabled && committed.incrementAndGet() == failAfter) {
            throw new IllegalStateException(
                    "simulated interruption after " + failAfter + " devices");
        }
    }
}
