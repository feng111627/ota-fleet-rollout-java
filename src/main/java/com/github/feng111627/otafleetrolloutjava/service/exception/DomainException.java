package com.github.feng111627.otafleetrolloutjava.service.exception;

import com.github.feng111627.otafleetrolloutjava.model.RejectReason;

/** 领域异常：携带可区分的拒绝原因。 */
public class DomainException extends RuntimeException {
    private final RejectReason reason;

    public DomainException(RejectReason reason, String detail) {
        super(reason.getDescription() + " | " + detail);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
