package com.github.feng111627.otafleetrolloutjava.compat;

/**
 * 兼容性判定结果。兼容时 {@link #compatible()} 为 true；
 * 不兼容时 {@link #reason()} 给出精确拒绝原因，{@link #detail()} 说明判定依据。
 */
public record CompatibilityResult(boolean compatible, RejectReason reason, String detail) {

    public static CompatibilityResult ok() {
        return new CompatibilityResult(true, null, "compatible");
    }

    public static CompatibilityResult reject(RejectReason reason, String detail) {
        return new CompatibilityResult(false, reason, detail);
    }
}
