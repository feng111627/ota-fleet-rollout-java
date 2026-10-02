package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 一次“推进到下一波动”操作的结果。
 *
 * @param advanced          是否真正放开了一个新波次（重复/提前推进时为 false）
 * @param reasonCode        未推进时的机器可判原因（ADVANCED / WAVE_NOT_FINISHED / ALREADY_LAST_WAVE ...）
 * @param previousWaveIndex 推进前处于 ACTIVE 的波次下标（无则 -1）
 * @param activeWaveIndex   操作后当前 ACTIVE 波次下标；批次已结束时为 -1
 * @param batchStatus       操作后批次状态
 * @param detail            判定说明
 */
public record WaveAdvanceResult(boolean advanced,
                                String reasonCode,
                                int previousWaveIndex,
                                int activeWaveIndex,
                                BatchStatus batchStatus,
                                String detail) {
}
