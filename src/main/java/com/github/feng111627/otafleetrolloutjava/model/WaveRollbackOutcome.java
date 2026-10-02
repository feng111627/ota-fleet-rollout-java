package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 单个波次内设备的回滚处置汇总（回滚结果按波次分别给出）。
 *
 * @param waveNo         波次序号（0 起）
 * @param status         回滚后波次状态（放开过的为 ROLLED_BACK，未放开的保持 PENDING）
 * @param deviceResults  本波每台设备的处置结果
 * @param rolledBack     本波恢复成功设备数
 * @param skipped        本波未开始、跳过处置的设备数
 * @param failed         本波需人工介入的设备数（状态不明/恢复失败）
 */
public record WaveRollbackOutcome(int waveNo,
                                  WaveStatus status,
                                  List<RollbackDeviceResult> deviceResults,
                                  int rolledBack,
                                  int skipped,
                                  int failed) {
}
