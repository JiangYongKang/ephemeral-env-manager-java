package com.github.highcumontoa.ephemeralenvmanagerjava.recovery;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ResourceBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动恢复：加载快照、恢复租约状态与资源占用、继续未完成的回收。
 * 恢复后状态保持可解释：ACTIVE 未到期继续有效；到期/回收中的立即进入回收流程。
 */
@Component
public class RecoveryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final SnapshotStore snapshotStore;
    private final LeaseRepository repository;
    private final ResourceBackend backend;
    private final Reclaimer reclaimer;

    public RecoveryService(SnapshotStore snapshotStore, LeaseRepository repository,
                           ResourceBackend backend, Reclaimer reclaimer) {
        this.snapshotStore = snapshotStore;
        this.repository = repository;
        this.backend = backend;
        this.reclaimer = reclaimer;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Lease> snapshot = snapshotStore.load();
        if (snapshot.isEmpty()) {
            log.info("recovery: no snapshot, starting empty");
            return;
        }
        repository.replaceAll(snapshot);

        long now = System.currentTimeMillis();
        int reallocated = 0;
        for (Lease lease : snapshot) {
            // 旧版本快照可能缺少 stateEnteredAtMillis：以恢复时刻归一化，保证停留时长可解释。
            if (lease.getStateEnteredAtMillis() <= 0) {
                lease.setStateEnteredAtMillis(now);
            }
            // 未终结且未摘除的租约在重启前占用了底层资源，恢复占用以保持配额一致。
            // RECLAIM_STUCK 已在升级时从容量账目中摘除，重启后同样不占容量。
            if (lease.getState() != LeaseState.RELEASED
                    && lease.getState() != LeaseState.RECLAIM_STUCK) {
                backend.allocate(lease.getName());
                reallocated++;
            }
        }
        log.info("recovery: restored {} leases, reallocated {} resources", snapshot.size(), reallocated);

        // 到期与回收中的租约立即继续回收；失败由后台扫描重试。
        int reclaimed = reclaimer.reclaimExpired();
        log.info("recovery: immediate reclaim pass finished, reclaimed={}", reclaimed);
    }
}
