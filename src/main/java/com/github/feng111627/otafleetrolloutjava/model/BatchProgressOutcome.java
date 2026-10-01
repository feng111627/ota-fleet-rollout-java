package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 单次上报在批次锁内应用后的结果（供编排器决定版本更新与熔断）。
 */
public record BatchProgressOutcome(boolean accepted,
                                   boolean duplicate,
                                   DeviceUpgradeState deviceState,
                                   boolean thresholdReached,
                                   String detail) {
}
