package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 一次“推进到下一波”动作的结果。
 *
 * <p>重复推进、不能推进、批次已回滚等场景都用同一结构返回（不抛异常的情况下），
 * {@link #advanced} 表示本次调用是否真正放开了一个新波次/完成了批次，
 * 便于并发或重复触发时判定幂等。
 *
 * @param advanced       本次调用是否真正发生了一次状态推进
 * @param completed      推进后批次是否已全部波次收口（COMPLETED）
 * @param currentWaveNo  动作后当前波次号（0 起；批次完成后指向最后一波）
 * @param reason         判定依据（已在当前波 / 波次未收口 / 已熔断 / 已回滚等）
 * @param batchStatus    动作后的批次状态
 */
public record WaveProgressOutcome(boolean advanced,
                                  boolean completed,
                                  int currentWaveNo,
                                  String reason,
                                  BatchStatus batchStatus) {
}
