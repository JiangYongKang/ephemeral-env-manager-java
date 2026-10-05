package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 临时环境租约的生命周期状态。
 */
public enum LeaseState {
    ACTIVE,
    EXPIRED,
    RELEASING,
    /**
     * 卡死兜底态：回收在 RELEASING 停留超过可配置阈值后升级到此态，
     * 并从容量账目中摘除（不再占配额），不再被后台扫描自动重试，
     * 仍可通过手动重试补救。可区分、可识别、可追溯，且随快照持久化。
     */
    QUARANTINED,
    RELEASED
}
