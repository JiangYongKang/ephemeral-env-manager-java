package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 一个临时环境租约。
 */
public class Lease {

    private String name;
    private String owner;
    private LeaseState state;
    private long createdAtMillis;
    private long expiresAtMillis;
    private int leaseVersion;
    private int releaseAttempts;
    /** 进入当前状态的时刻（毫秒），用于观测“在该阶段停留了多久”。 */
    private long stateEnteredAtMillis;
    /** 最近一次归还失败的原因（无失败或未失败过为 null）。 */
    private String lastFailureReason;

    public Lease() {
    }

    /**
     * 迁移到新状态并记录进入时刻；所有状态变更应通过此方法，保证停留时长可观测。
     */
    public void transitionTo(LeaseState next, long nowMillis) {
        this.state = next;
        this.stateEnteredAtMillis = nowMillis;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public LeaseState getState() {
        return state;
    }

    public void setState(LeaseState state) {
        this.state = state;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public void setCreatedAtMillis(long createdAtMillis) {
        this.createdAtMillis = createdAtMillis;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public void setExpiresAtMillis(long expiresAtMillis) {
        this.expiresAtMillis = expiresAtMillis;
    }

    public int getLeaseVersion() {
        return leaseVersion;
    }

    public void setLeaseVersion(int leaseVersion) {
        this.leaseVersion = leaseVersion;
    }

    public int getReleaseAttempts() {
        return releaseAttempts;
    }

    public void setReleaseAttempts(int releaseAttempts) {
        this.releaseAttempts = releaseAttempts;
    }

    public long getStateEnteredAtMillis() {
        return stateEnteredAtMillis;
    }

    public void setStateEnteredAtMillis(long stateEnteredAtMillis) {
        this.stateEnteredAtMillis = stateEnteredAtMillis;
    }

    public String getLastFailureReason() {
        return lastFailureReason;
    }

    public void setLastFailureReason(String lastFailureReason) {
        this.lastFailureReason = lastFailureReason;
    }
}
