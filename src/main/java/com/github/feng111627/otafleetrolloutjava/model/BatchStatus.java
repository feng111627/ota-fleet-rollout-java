package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 批次生命周期状态。
 */
public enum BatchStatus {
    /** 已创建，尚未放开推送 */
    PENDING,
    /** 推送中，允许设备上报进度 */
    ACTIVE,
    /** 人工暂停，暂停期间拒绝上报 */
    PAUSED,
    /** 失败率超阈，触发熔断，正在/已回滚 */
    CIRCUIT_OPEN,
    /** 人工回滚完成 */
    ROLLED_BACK,
    /** 全部设备升级成功 */
    COMPLETED
}
