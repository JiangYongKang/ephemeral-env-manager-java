package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Clock;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回收编排：标记到期、执行资源归还、失败重试。
 *
 * 语义：
 * - 回收可重试且可重复执行而不产生副作用（归还幂等、状态迁移幂等）；
 * - 单个环境回收失败不阻塞其他环境；
 * - 与续租竞争时以锁内时钟判定为准，结论确定。
 */
@Component
public class Reclaimer {

    private static final Logger log = LoggerFactory.getLogger(Reclaimer.class);

    private final LeaseRepository repository;
    private final ResourceBackend backend;
    private final SnapshotStore snapshotStore;
    private final Clock clock;

    public Reclaimer(LeaseRepository repository, ResourceBackend backend,
                     SnapshotStore snapshotStore, Clock clock) {
        this.repository = repository;
        this.backend = backend;
        this.snapshotStore = snapshotStore;
        this.clock = clock;
    }

    /**
     * 扫描并回收所有到期租约，返回本次成功完成归还的租约数量。
     */
    public int reclaimExpired() {
        long now = clock.nowMillis();
        int reclaimed = 0;
        for (Lease lease : List.copyOf(repository.findAll())) {
            if (lease.getState() == LeaseState.ACTIVE && lease.getExpiresAtMillis() <= now) {
                if (reclaimOne(lease.getName())) {
                    reclaimed++;
                }
            } else if (lease.getState() == LeaseState.RELEASING) {
                // 之前释放/回收未完成的，继续重试。
                if (reclaimOne(lease.getName())) {
                    reclaimed++;
                }
            }
        }
        return reclaimed;
    }

    /**
     * 对单个租约执行回收：ACTIVE 且到期 -> RELEASING -> 归还资源 -> RELEASED。
     * EXPIRED 是续租竞争失败时的可解释中间态，同样在下一步进入 RELEASING。
     * 归还失败保持 RELEASING 并累计 releaseAttempts，等待下次重试。
     *
     * @return 本次是否完成归还（含已终结的幂等命中）。
     */
    public boolean reclaimOne(String name) {
        long now = clock.nowMillis();
        // 第一步：锁内完成到期判定与状态迁移，与续租的判定互斥，结论确定。
        Lease transitioned = repository.mutate(name, lease -> {
            switch (lease.getState()) {
                case ACTIVE -> {
                    if (lease.getExpiresAtMillis() <= now) {
                        lease.setState(LeaseState.RELEASING);
                    }
                }
                case EXPIRED -> lease.setState(LeaseState.RELEASING);
                default -> {
                    // RELEASING / RELEASED 无需迁移。
                }
            }
            return lease;
        });
        if (transitioned == null) {
            return true; // 已不存在，视为完成。
        }
        if (transitioned.getState() != LeaseState.RELEASING) {
            // ACTIVE 未到期 / RELEASED：无需动作，幂等返回。
            return transitioned.getState() == LeaseState.RELEASED;
        }

        // 第二步：归还底层资源（幂等）。失败仅影响本环境，不抛出以阻塞其他环境。
        try {
            backend.deallocate(name);
        } catch (ResourceException e) {
            repository.mutate(name, lease -> {
                lease.setReleaseAttempts(lease.getReleaseAttempts() + 1);
                return lease;
            });
            persist();
            log.warn("reclaim retry pending name={} attempts={} cause={}",
                    name, transitioned.getReleaseAttempts() + 1, e.getMessage());
            return false;
        }

        repository.mutate(name, lease -> {
            if (lease.getState() == LeaseState.RELEASING) {
                lease.setState(LeaseState.RELEASED);
            }
            return lease;
        });
        persist();
        log.info("reclaimed name={} attempts={}", name, transitioned.getReleaseAttempts());
        return true;
    }

    private void persist() {
        snapshotStore.save(repository.findAll().stream().toList());
    }
}
