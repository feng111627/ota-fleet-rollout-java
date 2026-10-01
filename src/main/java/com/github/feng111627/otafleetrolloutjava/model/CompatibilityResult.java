package com.github.feng111627.otafleetrolloutjava.model;

/** 兼容性判定结果，兼容时 reason 为 null。 */
public record CompatibilityResult(boolean compatible, RejectReason reason, String detail) {
    public static CompatibilityResult ok() {
        return new CompatibilityResult(true, null, "兼容");
    }

    public static CompatibilityResult reject(RejectReason reason, String detail) {
        if (reason == null) {
            throw new IllegalArgumentException("拒绝原因不能为空");
        }
        return new CompatibilityResult(false, reason, detail);
    }
}
