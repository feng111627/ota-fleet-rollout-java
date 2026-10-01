package com.github.feng111627.otafleetrolloutjava.model;

/** 兼容性/操作被拒绝的可区分原因。 */
public enum RejectReason {
    DEVICE_NOT_FOUND("设备不存在"),
    FIRMWARE_NOT_FOUND("固件包不存在"),
    MODEL_MISMATCH("设备型号与固件目标型号不匹配"),
    VERSION_TOO_LOW("设备当前版本低于固件要求的最低可升级版本"),
    DEPENDENCY_MISSING("设备缺少固件依赖的能力条件"),
    BATCH_NOT_FOUND("发布批次不存在"),
    BATCH_NOT_ACTIVE("批次当前状态不允许该操作"),
    STAGE_NOT_OPEN("设备所在批次档位尚未放开"),
    DEVICE_NOT_IN_BATCH("设备不属于该批次"),
    INVALID_PHASE("设备上报的阶段无法识别"),
    ILLEGAL_TRANSITION("设备状态不允许该流转");

    private final String description;

    RejectReason(String description) { this.description = description; }

    public String getDescription() { return description; }
}
