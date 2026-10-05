package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;

/**
 * 一条“还没回收干净”环境的只读进度视图。
 *
 * @param millisInStage 在当前阶段已停留的毫秒数（查询时刻 - 进入该状态的时刻）
 */
public record ReclaimProgress(String name, String owner, LeaseState stage,
                              int releaseAttempts, String lastReclaimError,
                              long millisInStage) {
}
