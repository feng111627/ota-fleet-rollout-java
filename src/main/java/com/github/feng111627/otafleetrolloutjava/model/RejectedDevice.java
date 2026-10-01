package com.github.feng111627.otafleetrolloutjava.model;

/** 建批次时因不兼容被拒绝的设备。 */
public record RejectedDevice(String deviceId, RejectReason reason, String detail) {
}
