package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Clock;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回收编排：标记到期、执行资源归还、失败重试、卡死兜底升级。
 *
 * 语义：
 * - 回收可重试且可重复执行而不产生副作用（归还幂等、状态迁移幂等）；
 * - 单个环境回收失败不阻塞其他环境；
 * - 与续租竞争时以锁内时钟判定为准，结论确定；
 * - 同一环境的回收/手动重试通过每环境锁串行，并发触发不会产生重复归还副作用，
 *   且不同环境之间互不干扰；
 * - 在 RELEASING 停留超过 {@code ephemeral.reclaim-stuck-threshold-millis} 的租约
 *   被升级为 QUARANTINED 并从容量账目摘除，不再自动重试，可手动重试补救。
 */
@Component
public class Reclaimer {

    private static final Logger log = LoggerFactory.getLogger(Reclaimer.class);

    private final LeaseRepository repository;
    private final ResourceBackend backend;
    private final SnapshotStore snapshotStore;
    private final EphemeralProperties properties;
    private final Clock clock;
    /** 每环境一把锁：串行化同一环境的回收关键段，环境之间互不阻塞。 */
    private final ConcurrentHashMap<String, Object> reclaimLocks = new ConcurrentHashMap<>();

    public Reclaimer(LeaseRepository repository, ResourceBackend backend,
                     SnapshotStore snapshotStore, EphemeralProperties properties, Clock clock) {
        this.repository = repository;
        this.backend = backend;
        this.snapshotStore = snapshotStore;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 扫描并回收所有到期租约，返回本次成功完成归还的租约数量。
     * 顺带执行卡死兜底：停留过久的 RELEASING 升级为 QUARANTINED 并摘除容量占用。
     */
    public int reclaimExpired() {
        long now = clock.nowMillis();
        int reclaimed = 0;
        for (Lease lease : List.copyOf(repository.findAll())) {
            LeaseState state = lease.getState();
            if (state == LeaseState.ACTIVE && lease.getExpiresAtMillis() <= now) {
                if (reclaimOne(lease.getName())) {
                    reclaimed++;
                }
            } else if (state == LeaseState.EXPIRED || state == LeaseState.RELEASING) {
                // 之前释放/回收未完成的，继续重试。
                if (reclaimOne(lease.getName())) {
                    reclaimed++;
                }
            }
            // QUARANTINED / RELEASED 不进入自动扫描。
            escalateIfStuck(lease.getName(), now);
        }
        return reclaimed;
    }

    /**
     * 对单个租约执行回收：ACTIVE 且到期 -> RELEASING -> 归还资源 -> RELEASED。
     * EXPIRED 是续租竞争失败时的可解释中间态，同样在下一步进入 RELEASING。
     * 归还失败保持 RELEASING 并累计 releaseAttempts、记录 lastFailureReason，等待下次重试。
     * 同一环境的并发调用在每环境锁内串行，归还副作用不会重复。
     *
     * @return 本次是否完成归还（含已终结的幂等命中）。
     */
    public boolean reclaimOne(String name) {
        synchronized (lockOf(name)) {
            long now = clock.nowMillis();
            // 第一步：锁内完成到期判定与状态迁移，与续租的判定互斥，结论确定。
            Lease transitioned = repository.mutate(name, lease -> {
                switch (lease.getState()) {
                    case ACTIVE -> {
                        if (lease.getExpiresAtMillis() <= now) {
                            lease.transitionTo(LeaseState.RELEASING, now);
                        }
                    }
                    case EXPIRED -> lease.transitionTo(LeaseState.RELEASING, now);
                    default -> {
                        // RELEASING / QUARANTINED / RELEASED 无需迁移。
                    }
                }
                return lease;
            });
            if (transitioned == null) {
                return true; // 已不存在，视为完成。
            }
            if (transitioned.getState() != LeaseState.RELEASING) {
                // ACTIVE 未到期 / QUARANTINED / RELEASED：无需动作，幂等返回。
                return transitioned.getState() == LeaseState.RELEASED;
            }

            // 第二步：归还底层资源（幂等）。失败仅影响本环境，不抛出以阻塞其他环境。
            try {
                backend.deallocate(name);
            } catch (ResourceException e) {
                repository.mutate(name, lease -> {
                    lease.setReleaseAttempts(lease.getReleaseAttempts() + 1);
                    lease.setLastFailureReason(e.getMessage());
                    return lease;
                });
                persist();
                log.warn("reclaim retry pending name={} attempts={} cause={}",
                        name, transitioned.getReleaseAttempts() + 1, e.getMessage());
                return false;
            }

            repository.mutate(name, lease -> {
                if (lease.getState() == LeaseState.RELEASING) {
                    lease.transitionTo(LeaseState.RELEASED, now);
                }
                return lease;
            });
            persist();
            log.info("reclaimed name={} attempts={}", name, transitioned.getReleaseAttempts());
            return true;
        }
    }

    /**
     * 手动触发单个环境的回收重试（定点补救）。与后台扫描共用每环境锁：
     * 并发触发同一环境不会产生重复归还，也不干扰其他环境。
     * 对 QUARANTINED 环境会先迁回 RELEASING 再尝试归还，成功即 RELEASED。
     */
    public ReclaimRetryResult retryOne(String name) {
        synchronized (lockOf(name)) {
            Lease lease = repository.find(name).orElse(null);
            if (lease == null) {
                log.info("manual reclaim retry name={} outcome=NOT_FOUND", name);
                return new ReclaimRetryResult(ReclaimRetryOutcome.NOT_FOUND, name, null, 0);
            }
            if (lease.getState() == LeaseState.RELEASED) {
                log.info("manual reclaim retry name={} outcome=ALREADY_RELEASED", name);
                return new ReclaimRetryResult(ReclaimRetryOutcome.ALREADY_RELEASED, name,
                        LeaseState.RELEASED, lease.getReleaseAttempts());
            }
            long now = clock.nowMillis();
            if (lease.getState() == LeaseState.ACTIVE && lease.getExpiresAtMillis() > now) {
                // 仍在有效租期内的环境绝不能被回收动作触碰。
                log.info("manual reclaim retry name={} outcome=NOT_DUE expiresAt={} now={}",
                        name, lease.getExpiresAtMillis(), now);
                return new ReclaimRetryResult(ReclaimRetryOutcome.NOT_DUE, name,
                        LeaseState.ACTIVE, lease.getReleaseAttempts());
            }
            if (lease.getState() == LeaseState.QUARANTINED) {
                // 兜底态的定点补救：迁回 RELEASING 重试，成功即终结。
                repository.mutate(name, l -> {
                    l.transitionTo(LeaseState.RELEASING, now);
                    return l;
                });
                persist();
            }
            reclaimOne(name);
            Lease after = repository.find(name).orElseThrow();
            log.info("manual reclaim retry name={} outcome=TRIGGERED state={} attempts={}",
                    name, after.getState(), after.getReleaseAttempts());
            return new ReclaimRetryResult(ReclaimRetryOutcome.TRIGGERED, name,
                    after.getState(), after.getReleaseAttempts());
        }
    }

    /**
     * 卡死兜底：在 RELEASING 停留超过阈值的租约升级为 QUARANTINED，
     * 并从容量账目摘除（evict 幂等），使正常创建能拿到本该空出的容量。
     * 只处理 RELEASING：ACTIVE（含有效租期内）环境绝不因此被套牢或提前回收。
     */
    private boolean escalateIfStuck(String name, long now) {
        long threshold = properties.getReclaimStuckThresholdMillis();
        if (threshold <= 0) {
            return false;
        }
        synchronized (lockOf(name)) {
            Lease lease = repository.find(name).orElse(null);
            if (lease == null || lease.getState() != LeaseState.RELEASING) {
                return false;
            }
            long since = lease.getStateEnteredAtMillis() > 0
                    ? lease.getStateEnteredAtMillis() : lease.getCreatedAtMillis();
            if (now - since < threshold) {
                return false;
            }
            backend.evict(name);
            repository.mutate(name, l -> {
                if (l.getState() == LeaseState.RELEASING) {
                    l.transitionTo(LeaseState.QUARANTINED, now);
                }
                return l;
            });
            persist();
            log.warn("reclaim escalated to QUARANTINED name={} stuckMillis={} attempts={} lastFailure={}",
                    name, now - since, lease.getReleaseAttempts(), lease.getLastFailureReason());
            return true;
        }
    }

    private Object lockOf(String name) {
        return reclaimLocks.computeIfAbsent(name, k -> new Object());
    }

    private void persist() {
        snapshotStore.save(repository.findAll().stream().toList());
    }
}
