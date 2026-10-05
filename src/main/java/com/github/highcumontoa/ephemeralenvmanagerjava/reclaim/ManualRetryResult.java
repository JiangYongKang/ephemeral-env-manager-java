package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;

/**
 * 手动回收重试的结果视图。
 */
public record ManualRetryResult(String name, ReclaimRetryOutcome outcome,
                                LeaseState state, int releaseAttempts, String detail) {
}
