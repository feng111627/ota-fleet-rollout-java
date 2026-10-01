package com.github.feng111627.otafleetrolloutjava.api;

public record ReportRequest(String deviceId, String phase, Integer progress, String message) {
}
