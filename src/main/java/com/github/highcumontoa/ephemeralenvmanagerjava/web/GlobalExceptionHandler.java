package com.github.highcumontoa.ephemeralenvmanagerjava.web;

import com.github.highcumontoa.ephemeralenvmanagerjava.error.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.RejectReason;
import com.github.highcumontoa.ephemeralenvmanagerjava.web.dto.ErrorBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 业务拒绝到 HTTP 响应的映射，reason 字段保持可机器区分：
 * <ul>
 *   <li>NOT_FOUND → 404；ALREADY_RELEASED → 410；INVALID_REQUEST/报文不可读 → 400；</li>
 *   <li>NAME_CONFLICT / QUOTA_EXCEEDED / NOT_OWNER / LEASE_EXPIRED / RECLAIM_FAILED → 409。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LeaseException.class)
    public ResponseEntity<ErrorBody> handleLease(LeaseException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_RELEASED -> HttpStatus.GONE;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        log.info("[api] 拒绝请求 reason={} status={} message={}", ex.getReason(), status, ex.getMessage());
        return ResponseEntity.status(status)
                .body(new ErrorBody(ex.getReason().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody(RejectReason.INVALID_REQUEST.name(), "请求体不可读或格式错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("[api] 未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("reason", "INTERNAL_ERROR", "message", String.valueOf(ex.getMessage())));
    }
}
