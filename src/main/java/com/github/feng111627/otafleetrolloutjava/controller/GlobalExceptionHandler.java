package com.github.feng111627.otafleetrolloutjava.controller;

import com.github.feng111627.otafleetrolloutjava.service.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, String>> handleDomain(DomainException ex) {
        log.warn("[请求被拒绝] reason={}, message={}", ex.getReason(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "reason", ex.getReason().name(),
                "description", ex.getReason().getDescription(),
                "detail", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException ex) {
        log.warn("[请求参数非法] message={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "reason", "INVALID_ARGUMENT",
                "detail", String.valueOf(ex.getMessage())));
    }
}
