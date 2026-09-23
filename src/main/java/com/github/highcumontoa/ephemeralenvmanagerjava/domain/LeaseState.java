package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 临时环境租约的生命周期状态。
 */
public enum LeaseState {
    ACTIVE,
    EXPIRED,
    RELEASING,
    RELEASED
}
