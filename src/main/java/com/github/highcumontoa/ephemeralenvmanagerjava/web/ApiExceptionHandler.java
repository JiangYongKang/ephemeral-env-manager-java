package com.github.highcumontoa.ephemeralenvmanagerjava.web;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.RejectReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将 {@link LeaseException} 映射为带可区分 reason 的 HTTP 响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LeaseException.class)
    public ResponseEntity<Map<String, Object>> handle(LeaseException ex) {
        RejectReason reason = ex.getReason();
        HttpStatus status = switch (reason) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NAME_CONFLICT, ALREADY_RELEASED, NOT_ACTIVE -> HttpStatus.CONFLICT;
            case NOT_OWNER -> HttpStatus.FORBIDDEN;
            case QUOTA_EXCEEDED -> HttpStatus.SERVICE_UNAVAILABLE;
            case INVALID_TTL -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of(
                "reason", reason.name(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
