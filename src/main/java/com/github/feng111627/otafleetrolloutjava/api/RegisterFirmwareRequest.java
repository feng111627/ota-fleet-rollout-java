package com.github.feng111627.otafleetrolloutjava.api;

import java.util.List;

public record RegisterFirmwareRequest(String id, String version, String targetModel,
                                      String minApplicableVersion, List<String> requiredCapabilities) {
}
