package com.github.highcumontoa.ephemeralenvmanagerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可通过 application.properties 覆盖的本地配置。
 */
@Component
@ConfigurationProperties(prefix = "ephemeral")
public class EphemeralProperties {

    /** 同时存活环境的容量上限。 */
    private int maxActiveEnvironments = 4;

    /** 默认租期（毫秒）。 */
    private long defaultTtlMillis = 60_000L;

    /** 允许的最大续租时长（毫秒）。 */
    private long maxTtlMillis = 3_600_000L;

    /** 后台回收扫描间隔（毫秒）。 */
    private long reclaimIntervalMillis = 2_000L;

    /** 本地快照文件路径。 */
    private String snapshotPath = "data/leases-snapshot.json";

    /** 回收失败达到该次数后升级为 RECLAIM_STUCK 并从容量账目中摘除。 */
    private int reclaimStuckAfterAttempts = 5;

    /** 在 RELEASING 停留超过该时长（毫秒）即升级为 RECLAIM_STUCK。 */
    private long reclaimStuckAfterMillis = 300_000L;

    public int getMaxActiveEnvironments() {
        return maxActiveEnvironments;
    }

    public void setMaxActiveEnvironments(int maxActiveEnvironments) {
        this.maxActiveEnvironments = maxActiveEnvironments;
    }

    public long getDefaultTtlMillis() {
        return defaultTtlMillis;
    }

    public void setDefaultTtlMillis(long defaultTtlMillis) {
        this.defaultTtlMillis = defaultTtlMillis;
    }

    public long getMaxTtlMillis() {
        return maxTtlMillis;
    }

    public void setMaxTtlMillis(long maxTtlMillis) {
        this.maxTtlMillis = maxTtlMillis;
    }

    public long getReclaimIntervalMillis() {
        return reclaimIntervalMillis;
    }

    public void setReclaimIntervalMillis(long reclaimIntervalMillis) {
        this.reclaimIntervalMillis = reclaimIntervalMillis;
    }

    public String getSnapshotPath() {
        return snapshotPath;
    }

    public void setSnapshotPath(String snapshotPath) {
        this.snapshotPath = snapshotPath;
    }

    public int getReclaimStuckAfterAttempts() {
        return reclaimStuckAfterAttempts;
    }

    public void setReclaimStuckAfterAttempts(int reclaimStuckAfterAttempts) {
        this.reclaimStuckAfterAttempts = reclaimStuckAfterAttempts;
    }

    public long getReclaimStuckAfterMillis() {
        return reclaimStuckAfterMillis;
    }

    public void setReclaimStuckAfterMillis(long reclaimStuckAfterMillis) {
        this.reclaimStuckAfterMillis = reclaimStuckAfterMillis;
    }
}
