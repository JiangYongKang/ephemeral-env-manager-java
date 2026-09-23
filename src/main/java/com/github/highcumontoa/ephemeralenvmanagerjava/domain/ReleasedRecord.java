package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 已释放租约的历史记录，用于把"从未存在"与"曾经存在但已释放"两类拒绝原因区分开。
 */
public class ReleasedRecord {

    private String name;
    private String owner;
    private long releasedAtMillis;
    private String reason;

    public ReleasedRecord() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public long getReleasedAtMillis() { return releasedAtMillis; }
    public void setReleasedAtMillis(long releasedAtMillis) { this.releasedAtMillis = releasedAtMillis; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
