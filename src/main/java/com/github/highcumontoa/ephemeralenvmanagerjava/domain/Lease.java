package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 一份临时环境租约的可持久化状态（POJO，供 JSON 序列化与重启恢复使用）。
 */
public class Lease {

    private String name;
    private String owner;
    private String resourceId;
    private long createdAtMillis;
    private long deadlineMillis;
    private long updatedAtMillis;
    private LeaseStatus status;
    private long version;
    private int reclaimAttempts;
    private String lastErrorMessage;

    public Lease() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public void setCreatedAtMillis(long createdAtMillis) { this.createdAtMillis = createdAtMillis; }
    public long getDeadlineMillis() { return deadlineMillis; }
    public void setDeadlineMillis(long deadlineMillis) { this.deadlineMillis = deadlineMillis; }
    public long getUpdatedAtMillis() { return updatedAtMillis; }
    public void setUpdatedAtMillis(long updatedAtMillis) { this.updatedAtMillis = updatedAtMillis; }
    public LeaseStatus getStatus() { return status; }
    public void setStatus(LeaseStatus status) { this.status = status; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public int getReclaimAttempts() { return reclaimAttempts; }
    public void setReclaimAttempts(int reclaimAttempts) { this.reclaimAttempts = reclaimAttempts; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
}
