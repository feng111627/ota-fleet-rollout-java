package com.github.feng111627.otafleetrolloutjava.model;

/** 批次整体状态。 */
public enum BatchStatus {
    CREATED,        // 已创建，尚未放开任何档位
    IN_PROGRESS,    // 有档位放开中
    PAUSED,         // 已暂停
    CIRCUIT_OPEN,   // 熔断，正在回滚
    ROLLED_BACK,    // 回滚完成
    COMPLETED       // 全部设备到达终态
}
