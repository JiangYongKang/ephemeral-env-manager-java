package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

/**
 * 「还没回收干净」的租约所处的回收阶段（由状态与重试进度推导，便于运维阅读）。
 */
public enum ReclaimPhase {
    /** 已到期/已判定失效，等待后台扫描进入回收。 */
    WAITING_RECLAIM,
    /** 回收进行中（尚未发生过失败）。 */
    RECLAIMING,
    /** 回收失败，等待重试（已累计至少一次失败）。 */
    RETRY_PENDING,
    /** 卡死兜底：超过阈值被升级，已从容量账目摘除，不再自动重试。 */
    QUARANTINED
}
