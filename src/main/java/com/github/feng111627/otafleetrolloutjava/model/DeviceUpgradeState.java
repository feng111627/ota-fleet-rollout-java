package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 单台设备在批次内的升级状态机。
 */
public enum DeviceUpgradeState {
    /** 尚未开始升级（未收到任何进度） */
    NOT_STARTED,
    /** 下载/安装中（升级进行中） */
    IN_PROGRESS,
    /** 升级成功 */
    SUCCEEDED,
    /** 升级失败 */
    FAILED,
    /** 回滚成功（曾升级成功后被恢复） */
    ROLLBACK_SUCCEEDED,
    /** 无需回滚（回滚时还没开始升级） */
    ROLLBACK_SKIPPED,
    /** 回滚失败（设备升级失败，无法远程恢复） */
    ROLLBACK_FAILED
}
