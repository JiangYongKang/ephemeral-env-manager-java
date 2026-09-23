package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 临时环境租约生命周期状态。
 *
 * <pre>
 * ACTIVE ──到期──▶ EXPIRED ──回收开始──▶ RECLAIMING ──成功──▶ RELEASED
 *                       ▲                    │
 *                       │                    └──失败──▶ RECLAIM_FAILED ──重试──▶ RECLAIMING
 * ACTIVE ──主动释放──▶ RECLAIMING ... ──▶ RELEASED
 * </pre>
 */
public enum LeaseStatus {
    /** 已占用且处于有效租期内 */
    ACTIVE,
    /** 租期已到期，等待回收（仍是待回收进度的一部分，重启可恢复） */
    EXPIRED,
    /** 回收执行中：底层资源正在被归还 */
    RECLAIMING,
    /** 回收失败，等待下一次重试 */
    RECLAIM_FAILED,
    /** 已释放：资源已归还，名称可被重新占用（保留于历史记录中以便区分拒绝原因） */
    RELEASED
}
