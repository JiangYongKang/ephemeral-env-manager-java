package com.github.highcumontoa.ephemeralenvmanagerjava.web.dto;

/** 统一错误响应：reason 为可机器区分的拒绝原因枚举名。 */
public record ErrorBody(String reason, String message) {
}
