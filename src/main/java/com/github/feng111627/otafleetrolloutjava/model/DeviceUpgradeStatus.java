package com.github.feng111627.otafleetrolloutjava.model;

/** 批次内单台设备的升级状态。 */
public enum DeviceUpgradeStatus {
    PENDING,        // 尚未推送
    IN_PROGRESS,    // 已推送/升级中
    SUCCESS,        // 升级成功
    FAILED,         // 升级报错
    TIMEOUT,        // 升级超时
    ROLLING_BACK,   // 升级成功后正在回滚
    ROLLED_BACK,    // 已回退到旧版本
    NOT_UPGRADED    // 回滚时确认未升级，保持原状
}
