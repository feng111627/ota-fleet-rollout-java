package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/** 建批次结果：批次与被拒绝设备清单（原因可区分）。 */
public record RolloutCreateResult(String batchId, List<RejectedDevice> rejectedDevices) {
}
