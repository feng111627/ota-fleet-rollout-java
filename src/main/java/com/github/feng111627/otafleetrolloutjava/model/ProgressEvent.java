package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 设备心跳 / 升级进度上报事件。
 *
 * @param batchId  批次 ID
 * @param deviceId 设备 ID
 * @param phase    上报阶段
 * @param message  可选附加信息（失败原因等）
 * @param eventId  上报方生成的去重 ID；为空则按 (deviceId, phase) 幂等
 */
public record ProgressEvent(String batchId,
                            String deviceId,
                            ProgressPhase phase,
                            String message,
                            String eventId) {

    public static ProgressEvent heartbeat(String batchId, String deviceId) {
        return new ProgressEvent(batchId, deviceId, ProgressPhase.HEARTBEAT, null, null);
    }
}
