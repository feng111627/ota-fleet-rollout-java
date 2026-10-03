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
     * 曾放开过（ACTIVE/FINISHED）、随后随整批回滚完成而终结：
     * 波内设备的处置结果看各自的 ROLLBACK_* 状态与回滚统计。
     * 与 PENDING 区分：PENDING 表示“压根没轮到、原样没动”。
     */
    ROLLED_BACK
}
