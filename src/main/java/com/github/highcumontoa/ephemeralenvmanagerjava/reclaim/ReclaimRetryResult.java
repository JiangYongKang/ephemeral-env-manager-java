package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;

/**
 * 手动回收重试的结果：可区分的结局 + 触发后租约的最新状态与累计重试次数。
 */
public record ReclaimRetryResult(ReclaimRetryOutcome outcome, String name,
                                 LeaseState state, int releaseAttempts) {
}
