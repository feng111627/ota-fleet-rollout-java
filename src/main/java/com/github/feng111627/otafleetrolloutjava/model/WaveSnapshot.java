package com.github.feng111627.otafleetrolloutjava.model;

import java.util.List;

/**
 * 单个波次的只读进度与统计。
 *
 * @param waveNo         波次序号（0 起，按建批顺序）
 * @param status         波次状态
 * @param deviceIds      本波设备（保持建批给定顺序）
 * @param total          本波设备总数
 * @param succeeded      本波已升级成功设备数
 * @param failed         本波已升级失败设备数
 * @param notStarted     本波尚未开始设备数
 * @param inProgress     本波升级中设备数
 * @param rolledBack     回滚后本波恢复成功的设备数
 * @param rollbackSkipped 回滚后本波无需回滚（未开始）的设备数
 * @param rollbackFailed 回滚后本波需人工介入的设备数
 */
public record WaveSnapshot(int waveNo,
                           WaveStatus status,
                           List<String> deviceIds,
                           int total,
                           int succeeded,
                           int failed,
                           int notStarted,
                           int inProgress,
                           int rolledBack,
                           int rollbackSkipped,
                           int rollbackFailed) {
}
