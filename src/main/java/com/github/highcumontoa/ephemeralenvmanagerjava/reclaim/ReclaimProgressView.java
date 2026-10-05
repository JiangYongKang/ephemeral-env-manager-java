package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;

/**
 * 「还没回收干净」租约的只读进度视图。
 *
 * @param stuckMillis 在当前阶段停留的时长（毫秒）
 */
public record ReclaimProgressView(String name, String owner, LeaseState state,
                                  ReclaimPhase phase, int releaseAttempts,
                                  String lastFailureReason,
                                  long stateEnteredAtMillis, long stuckMillis) {
}
