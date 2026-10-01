package com.github.feng111627.otafleetrolloutjava.api;

import java.util.List;

public record CreateBatchRequest(String firmwareId, List<String> deviceIds, List<Integer> stagePercentages,
                                 Double failureRateThreshold, Integer minSamplesForCircuit) {
}
