package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

/**
 * 手动触发单个环境回收重试的可区分结果。
 */
public enum ReclaimRetryOutcome {
    /** 本次调用确实触发了一次归还尝试。 */
    TRIGGERED,
    /** 租约仍在有效租期内（ACTIVE 未到期），按规则不做任何回收动作。 */
    NOT_DUE,
    /** 租约已归还干净（终结态），无需重试。 */
    ALREADY_RELEASED,
    /** 租约不存在。 */
    NOT_FOUND
}
