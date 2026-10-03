package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 单个波次的生命周期状态。
 */
public enum WaveStatus {
    /** 尚未放开（前面还有波次在进行），设备上报被拒绝 */
    PENDING,
    /** 当前放开中，仅该波次设备接受进度上报 */
    ACTIVE,
    /** 波次内全部设备到达终态（SUCCEEDED/FAILED），等待推进下一波 */
    FINISHED,
    /**
     * 已放开过、随批次回滚完成处置（由 ACTIVE/FINISHED 收敛而来）。
     * 与 PENDING（压根没轮到、原样没动）区分：该波次的设备按各自回滚前状态
     * 被恢复/跳过/标记人工，见 {@code WaveSnapshot} 的回滚统计口径。
     */
    ROLLED_BACK
}
