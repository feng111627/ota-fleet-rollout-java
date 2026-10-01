package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 回滚启动点的一致性快照：采集完成后批次立即进入 CIRCUIT_OPEN，
 * 阻断一切新的进度上报。
 */
public record RollbackCapture(List<RollbackCaptureItem> items) {
}
