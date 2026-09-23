package com.github.highcumontoa.ephemeralenvmanagerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ephemeral-env.* 配置项。
 */
@ConfigurationProperties(prefix = "ephemeral-env")
public class LeaseProperties {

    /** 同时存活（非 RELEASED）环境的容量配额 */
    private int maxActiveLeases = 2;

    /** 默认租期（毫秒） */
    private long defaultTtlMillis = 60_000;

    /** 状态持久化 JSON 文件路径（空表示使用工作目录下的默认文件） */
    private String dataFile = "";

    /** 是否启用后台到期回收调度 */
    private boolean schedulerEnabled = false;

    /** 后台回收扫描间隔（毫秒） */
    private long reclaimIntervalMillis = 5_000;

    public int getMaxActiveLeases() { return maxActiveLeases; }
    public void setMaxActiveLeases(int maxActiveLeases) { this.maxActiveLeases = maxActiveLeases; }
    public long getDefaultTtlMillis() { return defaultTtlMillis; }
    public void setDefaultTtlMillis(long defaultTtlMillis) { this.defaultTtlMillis = defaultTtlMillis; }
    public String getDataFile() { return dataFile; }
    public void setDataFile(String dataFile) { this.dataFile = dataFile; }
    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
    public long getReclaimIntervalMillis() { return reclaimIntervalMillis; }
    public void setReclaimIntervalMillis(long reclaimIntervalMillis) { this.reclaimIntervalMillis = reclaimIntervalMillis; }
}
