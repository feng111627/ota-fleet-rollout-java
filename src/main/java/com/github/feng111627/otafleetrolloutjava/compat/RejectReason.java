package com.github.feng111627.otafleetrolloutjava.compat;

/**
 * 兼容性 / 登记类拒绝原因，每种原因使用不同 code，避免笼统失败。
 */
public enum RejectReason {
    DEVICE_NOT_FOUND("DEVICE_NOT_FOUND"),
    FIRMWARE_NOT_FOUND("FIRMWARE_NOT_FOUND"),
    DEVICE_ALREADY_REGISTERED("DEVICE_ALREADY_REGISTERED"),
    FIRMWARE_ALREADY_REGISTERED("FIRMWARE_ALREADY_REGISTERED"),
    MODEL_MISMATCH("MODEL_MISMATCH"),
    CURRENT_VERSION_TOO_LOW("CURRENT_VERSION_TOO_LOW"),
    DEPENDENCY_NOT_SATISFIED("DEPENDENCY_NOT_SATISFIED"),
    DEVICE_NOT_IN_BATCH("DEVICE_NOT_IN_BATCH");

    private final String code;

    RejectReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
