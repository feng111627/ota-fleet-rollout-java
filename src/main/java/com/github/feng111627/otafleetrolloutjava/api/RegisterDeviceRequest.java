package com.github.feng111627.otafleetrolloutjava.api;

import java.util.List;

public record RegisterDeviceRequest(String id, String model, String currentVersion, List<String> capabilities) {
}
