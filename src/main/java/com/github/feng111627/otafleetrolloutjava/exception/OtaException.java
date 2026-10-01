package com.github.feng111627.otafleetrolloutjava.exception;

/**
 * OTA 编排业务异常基类，携带可区分的原因码。
 */
public class OtaException extends RuntimeException {

    private final String reasonCode;

    public OtaException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
