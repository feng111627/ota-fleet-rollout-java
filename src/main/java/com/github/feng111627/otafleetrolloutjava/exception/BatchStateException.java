package com.github.feng111627.otafleetrolloutjava.exception;

/**
 * 批次状态不允许当前操作时抛出，原因码区分具体场景。
 */
public class BatchStateException extends OtaException {

    public BatchStateException(String reasonCode, String message) {
        super(reasonCode, message);
    }
}
