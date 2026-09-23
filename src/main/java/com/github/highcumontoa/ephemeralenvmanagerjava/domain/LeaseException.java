package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 携带 {@link RejectReason} 的业务拒绝异常。
 */
public class LeaseException extends RuntimeException {

    private final RejectReason reason;

    public LeaseException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
