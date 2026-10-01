package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 进度上报处理结果。
 *
 * @param accepted        本次上报是否首次生效（重复上报为 false，但不属于错误）
 * @param duplicate       是否为重复上报
 * @param deviceState     处理后设备在批次内的状态
 * @param batchStatus     处理后批次状态
 * @param circuitOpened   本次上报是否触发了熔断
 * @param detail          判定说明
 */
public record ProgressReportResult(boolean accepted,
                                   boolean duplicate,
                                   DeviceUpgradeState deviceState,
                                   BatchStatus batchStatus,
                                   boolean circuitOpened,
                                   String detail) {
}
