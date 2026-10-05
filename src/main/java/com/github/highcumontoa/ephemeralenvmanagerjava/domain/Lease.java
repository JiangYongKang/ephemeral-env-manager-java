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
    /** 进入当前状态的时间（毫秒），用于计算“在该阶段停留了多久”。 */
    private long stateEnteredAtMillis;
    /** 最近一次回收失败的原因（无失败记录时为 null）。 */
    private String lastReclaimError;

    public Lease() {
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

    public String getLastReclaimError() {
        return lastReclaimError;
    }

    public void setLastReclaimError(String lastReclaimError) {
        this.lastReclaimError = lastReclaimError;
    }
}
