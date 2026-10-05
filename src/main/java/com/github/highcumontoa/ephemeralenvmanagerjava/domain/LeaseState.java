package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 临时环境租约的生命周期状态。
 */
public enum LeaseState {
    ACTIVE,
    EXPIRED,
    RELEASING,
    /**
     * 回收长时间失败的兜底升级态：已从容量占用中摘除（不再阻挡新创建），
     * 仍可被后台扫描与手动重试继续归还，直至 RELEASED。
     */
    RECLAIM_STUCK,
    RELEASED
}
