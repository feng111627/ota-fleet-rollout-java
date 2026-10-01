package com.github.feng111627.otafleetrolloutjava.api;

public record CompatibilityResponse(boolean compatible, String reason, String detail) {
}
