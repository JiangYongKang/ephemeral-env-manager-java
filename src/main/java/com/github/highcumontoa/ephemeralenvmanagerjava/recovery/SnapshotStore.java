package com.github.highcumontoa.ephemeralenvmanagerjava.recovery;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;

import java.util.List;

/**
 * 租约状态与待回收进度的持久化抽象，用于重启恢复。
 */
public interface SnapshotStore {

    void save(List<Lease> leases);

    List<Lease> load();
}
