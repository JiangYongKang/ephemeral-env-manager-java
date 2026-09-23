package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 租约操作被拒绝的可区分原因。
 */
public enum RejectReason {
    /** 名称已被其他有效（未终结）租约占用。 */
    NAME_CONFLICT,
    /** 超出容量配额。 */
    QUOTA_EXCEEDED,
    /** 操作方不是租约归属方。 */
    NOT_OWNER,
    /** 租约名称不存在。 */
    NOT_FOUND,
    /** 租约已释放（终结态）。 */
    ALREADY_RELEASED,
    /** 租约已过期或回收中，不能执行该操作。 */
    NOT_ACTIVE,
    /** TTL 参数非法。 */
    INVALID_TTL
}
