package com.github.feng111627.otafleetrolloutjava.model;

/** 设备心跳/进度上报的阶段类型。 */
public enum ReportPhase {
    PROGRESS,       // 进度更新（可重复）
    SUCCESS,        // 升级成功（终态）
    FAILED,         // 升级报错（终态）
    TIMEOUT,        // 升级超时（终态）
    ROLLBACK_DONE   // 回滚完成确认
}
