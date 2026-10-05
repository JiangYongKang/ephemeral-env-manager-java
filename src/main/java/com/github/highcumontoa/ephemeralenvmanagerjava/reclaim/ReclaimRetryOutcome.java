package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

/**
 * 手动触发回收重试的可区分结果。
 */
public enum ReclaimRetryOutcome {
    /** 本次确实触发了重试，且归还完成（租约进入 RELEASED）。 */
    RETRY_SUCCEEDED,
    /** 本次确实触发了重试，但归还仍失败（停留在 RELEASING / RECLAIM_STUCK）。 */
    RETRY_FAILED,
    /** 租约已归还干净（RELEASED），本次未触发任何归还动作。 */
    ALREADY_RELEASED,
    /** 租约不存在。 */
    NOT_FOUND,
    /** 租约不在回收阶段（如 ACTIVE 且未到期），本次未触发归还动作。 */
    NOT_IN_RECLAIM
}
