package com.github.feng111627.otafleetrolloutjava.exception;

import com.github.feng111627.otafleetrolloutjava.compat.RejectReason;

/**
 * 兼容性校验未通过的异常，拒绝原因通过 {@link RejectReason} 精确区分。
 */
public class CompatibilityException extends OtaException {

    private final RejectReason reason;

    public CompatibilityException(RejectReason reason, String message) {
        super(reason.code(), message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
