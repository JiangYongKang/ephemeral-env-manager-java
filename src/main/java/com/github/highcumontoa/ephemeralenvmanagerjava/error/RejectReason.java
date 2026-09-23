package com.github.highcumontoa.ephemeralenvmanagerjava.error;

/**
 * 可区分的拒绝原因，调用方据此区分容量、归属、状态等不同失败。
 */
public enum RejectReason {
    /** 同名环境已被其他属主占用 */
    NAME_CONFLICT,
    /** 活跃环境数量已达容量配额上限 */
    QUOTA_EXCEEDED,
    /** 操作方不是该环境的归属方 */
    NOT_OWNER,
    /** 指定名称的环境从未创建过 */
    NOT_FOUND,
    /** 环境曾经存在但已释放，不能再续租/释放 */
    ALREADY_RELEASED,
    /** 环境已到期并进入回收流程，不能再续租（续租与到期竞争的确定结论） */
    LEASE_EXPIRED,
    /** 释放时底层资源归还失败：租约进入 RECLAIM_FAILED 等待重试 */
    RECLAIM_FAILED,
    /** 续租/释放请求缺少必要参数 */
    INVALID_REQUEST
}
