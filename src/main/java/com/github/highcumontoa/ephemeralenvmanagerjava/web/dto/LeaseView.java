package com.github.highcumontoa.ephemeralenvmanagerjava.web.dto;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;

/** 租约对外视图，含状态、到期时刻、回收尝试次数与最近失败原因等可解释信息。 */
public class LeaseView {
    private String name;
    private String owner;
    private String resourceId;
    private String status;
    private long createdAtMillis;
    private long deadlineMillis;
    private long updatedAtMillis;
    private long version;
    private int reclaimAttempts;
    private String lastErrorMessage;
    private boolean expired;

    public static LeaseView from(Lease lease, boolean expired) {
        LeaseView v = new LeaseView();
        v.name = lease.getName();
        v.owner = lease.getOwner();
        v.resourceId = lease.getResourceId();
        v.status = lease.getStatus().name();
        v.createdAtMillis = lease.getCreatedAtMillis();
        v.deadlineMillis = lease.getDeadlineMillis();
        v.updatedAtMillis = lease.getUpdatedAtMillis();
        v.version = lease.getVersion();
        v.reclaimAttempts = lease.getReclaimAttempts();
        v.lastErrorMessage = lease.getLastErrorMessage();
        v.expired = expired;
        return v;
    }

    public String getName() { return name; }
    public String getOwner() { return owner; }
    public String getResourceId() { return resourceId; }
    public String getStatus() { return status; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public long getDeadlineMillis() { return deadlineMillis; }
    public long getUpdatedAtMillis() { return updatedAtMillis; }
    public long getVersion() { return version; }
    public int getReclaimAttempts() { return reclaimAttempts; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public boolean isExpired() { return expired; }
}
