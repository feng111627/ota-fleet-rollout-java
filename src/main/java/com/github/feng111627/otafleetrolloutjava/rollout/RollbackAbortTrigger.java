package com.github.feng111627.otafleetrolloutjava.rollout;

/**
 * 回滚中止钩子：在每台设备处置前被调用，抛出异常可模拟“回滚整体执行到一半被报错/打断”。
 * 默认实现什么都不做。重试时不再对已处于回滚终态的设备调用（见 RollbackExecutor）。
 */
@FunctionalInterface
public interface RollbackAbortTrigger {

    RollbackAbortTrigger NOOP = (batchId, deviceId, beforeState) -> { };

    /**
     * @throws RuntimeException 抛出后整轮回滚立即中断，批次保持 CIRCUIT_OPEN
     */
    void check(String batchId, String deviceId,
               com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState beforeState);
}
