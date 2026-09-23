package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 创建请求的判定结果：created=true 表示本次实际占用；false 表示命中幂等的同名同属主租约。
 */
public record CreateResult(Lease lease, boolean created, String reason) {
}
