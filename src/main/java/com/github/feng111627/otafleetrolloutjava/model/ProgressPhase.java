package com.github.feng111627.otafleetrolloutjava.model;

/**
 * 设备上报的进度阶段。
 */
public enum ProgressPhase {
    /** 心跳，不改变升级状态，仅表示设备在线 */
    HEARTBEAT,
    /** 开始下载 */
    DOWNLOADING,
    /** 开始安装 */
    INSTALLING,
    /** 升级成功（终态） */
    SUCCEEDED,
    /** 升级失败（终态） */
    FAILED
}
