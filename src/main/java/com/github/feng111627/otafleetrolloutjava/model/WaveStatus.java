package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 单个波次的生命周期状态。
 */
public enum WaveStatus {
    /** 已建波但尚未放开（批次启动后只有第一波离开该状态） */
    PENDING,
    /** 已放开，正在接收本波设备的进度上报 */
    ACTIVE,
    /** 本波设备全部拿到终态且未触发熔断，已正常收口 */
    FINISHED,
    /** 批次回滚后，曾经放开过的波次的终态；从未放开的波次保持 PENDING */
    ROLLED_BACK
}
