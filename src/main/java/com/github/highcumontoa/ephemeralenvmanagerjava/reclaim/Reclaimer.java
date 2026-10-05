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

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回收编排：标记到期、执行资源归还、失败重试、卡死兜底升级。
 *
 * 语义：
 * - 回收可重试且可重复执行而不产生副作用（归还幂等、状态迁移幂等）；
 * - 同一环境的回收动作按名串行（per-name 锁）：并发手动重试/后台扫描不会
 *   对同一环境产生重复归还，也不会互相干扰；不同环境之间完全并行；
 * - 单个环境回收失败不阻塞其他环境；
 * - RELEASING 失败次数或停留时长超过可配置阈值时升级为 RECLAIM_STUCK，
 *   并从容量账目中摘除，正常创建不再被它挡住；真实归还仍由后续重试完成；
 * - 与续租竞争时以锁内时钟判定为准，结论确定。
 */
@Component
public class Reclaimer {

    private static final Logger log = LoggerFactory.getLogger(Reclaimer.class);

    private final LeaseRepository repository;
    private final ResourceBackend backend;
    private final SnapshotStore snapshotStore;
    private final EphemeralProperties properties;
    private final Clock clock;

    /** 按环境名串行化回收动作，保证同一环境的并发回收不产生重复归还。 */
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
     */
    public int reclaimExpired() {
        long now = clock.nowMillis();
        int reclaimed = 0;
        for (Lease lease : List.copyOf(repository.findAll())) {
            LeaseState state = lease.getState();
            boolean due = state == LeaseState.EXPIRED
                    || state == LeaseState.RELEASING
                    || state == LeaseState.RECLAIM_STUCK
                    || (state == LeaseState.ACTIVE && lease.getExpiresAtMillis() <= now);
            if (due && reclaimOne(lease.getName())) {
                reclaimed++;
            }
        }
        return reclaimed;
    }

    /**
     * 对单个租约执行回收：ACTIVE 且到期 / EXPIRED -> RELEASING -> 归还资源 -> RELEASED。
     * 归还失败保持 RELEASING 并累计 releaseAttempts、记录 lastReclaimError；
     * 失败次数或停留时长超阈值时升级为 RECLAIM_STUCK 并摘除容量占用。
     *
     * @return 本次是否完成归还（含已终结的幂等命中）。
     */
    public boolean reclaimOne(String name) {
        synchronized (lockFor(name)) {
            return doReclaim(name);
        }
    }

    /**
     * 手动触发一次回收重试（定点补救）。与后台扫描共用同一把按名锁：
     * 同一环境并发触发时串行执行，只有真正执行归还的那次会计为
     * {@link ReclaimRetryOutcome#RETRY_SUCCEEDED}/{@link ReclaimRetryOutcome#RETRY_FAILED}，
     * 其余并发调用看到的是确定的前置状态（如 ALREADY_RELEASED）。
     */
    public ManualRetryResult manualRetry(String name) {
        synchronized (lockFor(name)) {
            Optional<Lease> found = repository.find(name);
            if (found.isEmpty()) {
                log.info("manual reclaim retry name={} outcome=NOT_FOUND", name);
                return new ManualRetryResult(name, ReclaimRetryOutcome.NOT_FOUND, null, 0,
                        "lease not found");
            }
            Lease lease = found.get();
            long now = clock.nowMillis();
            switch (lease.getState()) {
                case RELEASED -> {
                    log.info("manual reclaim retry name={} outcome=ALREADY_RELEASED", name);
                    return new ManualRetryResult(name, ReclaimRetryOutcome.ALREADY_RELEASED,
                            LeaseState.RELEASED, lease.getReleaseAttempts(),
                            "lease already released");
                }
                case ACTIVE -> {
                    if (lease.getExpiresAtMillis() > now) {
                        // 仍在有效租期内：绝不因手动重试被提前回收。
                        log.info("manual reclaim retry name={} outcome=NOT_IN_RECLAIM", name);
                        return new ManualRetryResult(name, ReclaimRetryOutcome.NOT_IN_RECLAIM,
                                LeaseState.ACTIVE, lease.getReleaseAttempts(),
                                "lease still active until " + lease.getExpiresAtMillis());
                    }
                }
                default -> {
                    // EXPIRED / RELEASING / RECLAIM_STUCK：落入重试。
                }
            }
            boolean ok = doReclaim(name);
            Lease after = repository.find(name).orElse(lease);
            ReclaimRetryOutcome outcome = ok ? ReclaimRetryOutcome.RETRY_SUCCEEDED
                    : ReclaimRetryOutcome.RETRY_FAILED;
            log.info("manual reclaim retry name={} outcome={} state={} attempts={}",
                    name, outcome, after.getState(), after.getReleaseAttempts());
            return new ManualRetryResult(name, outcome, after.getState(),
                    after.getReleaseAttempts(),
                    ok ? "reclaim completed" : after.getLastReclaimError());
        }
    }

    /**
     * 只读查询：当前所有“还没回收干净”的环境进度。
     * 覆盖：等待回收（ACTIVE 已到期 / EXPIRED）、回收进行中（RELEASING）、
     * 回收失败等待重试（RELEASING 且 attempts>0）、已升级兜底（RECLAIM_STUCK）。
     *
     * 仅基于 findAll 的弱一致快照做纯计算：不取任何写锁、不改任何状态，
     * 因此不会阻塞并发的创建、续租与后台回收。
     */
    public List<ReclaimProgress> listPendingReclaims() {
        long now = clock.nowMillis();
        return repository.findAll().stream()
                .filter(lease -> switch (lease.getState()) {
                    case EXPIRED, RELEASING, RECLAIM_STUCK -> true;
                    case ACTIVE -> lease.getExpiresAtMillis() <= now;
                    case RELEASED -> false;
                })
                .map(lease -> new ReclaimProgress(lease.getName(), lease.getOwner(),
                        lease.getState(), lease.getReleaseAttempts(),
                        lease.getLastReclaimError(),
                        Math.max(0, now - lease.getStateEnteredAtMillis())))
                .sorted(Comparator.comparing(ReclaimProgress::name))
                .toList();
    }

    // ===== 内部实现（调用方必须已持有 name 对应的按名锁） =====

    private boolean doReclaim(String name) {
        long now = clock.nowMillis();
        // 第一步：锁内完成到期判定与状态迁移，与续租的判定互斥，结论确定。
        Lease transitioned = repository.mutate(name, lease -> {
            switch (lease.getState()) {
                case ACTIVE -> {
                    if (lease.getExpiresAtMillis() <= now) {
                        transition(lease, LeaseState.RELEASING, now);
                    }
                }
                case EXPIRED -> transition(lease, LeaseState.RELEASING, now);
                default -> {
                    // RELEASING / RECLAIM_STUCK / RELEASED 无需迁移。
                }
            }
            return lease;
        });
        if (transitioned == null) {
            return true; // 已不存在，视为完成。
        }
        LeaseState state = transitioned.getState();
        if (state != LeaseState.RELEASING && state != LeaseState.RECLAIM_STUCK) {
            // ACTIVE 未到期 / RELEASED：无需动作，幂等返回。
            return state == LeaseState.RELEASED;
        }

        // 第二步：归还底层资源（幂等）。失败仅影响本环境，不抛出以阻塞其他环境。
        try {
            backend.deallocate(name);
        } catch (ResourceException e) {
            repository.mutate(name, lease -> {
                lease.setReleaseAttempts(lease.getReleaseAttempts() + 1);
                lease.setLastReclaimError(e.getMessage());
                maybeEscalate(lease, now);
                return lease;
            });
            persist();
            Lease after = repository.find(name).orElse(transitioned);
            log.warn("reclaim retry pending name={} attempts={} state={} cause={}",
                    name, after.getReleaseAttempts(), after.getState(), e.getMessage());
            return false;
        }

        repository.mutate(name, lease -> {
            if (lease.getState() == LeaseState.RELEASING
                    || lease.getState() == LeaseState.RECLAIM_STUCK) {
                transition(lease, LeaseState.RELEASED, now);
                lease.setLastReclaimError(null);
            }
            return lease;
        });
        persist();
        log.info("reclaimed name={} attempts={}", name, transitioned.getReleaseAttempts());
        return true;
    }

    /**
     * 卡死兜底：失败次数达到阈值，或在 RELEASING 停留超过阈值时长，
     * 升级为 RECLAIM_STUCK 并把该环境从容量账目中摘除（真实归还留待后续重试）。
     * 只在回收失败路径调用，绝不会触及仍在有效租期内的 ACTIVE 租约。
     */
    private void maybeEscalate(Lease lease, long now) {
        if (lease.getState() != LeaseState.RELEASING) {
            return; // 已升级过的 RECLAIM_STUCK 保持原态，继续累计 attempts。
        }
        boolean attemptsExceeded = lease.getReleaseAttempts() >= properties.getReclaimStuckAfterAttempts();
        boolean tooLong = now - lease.getStateEnteredAtMillis() >= properties.getReclaimStuckAfterMillis();
        if (attemptsExceeded || tooLong) {
            transition(lease, LeaseState.RECLAIM_STUCK, now);
            backend.releaseAccounting(lease.getName());
            log.warn("reclaim escalated name={} attempts={} stuckMillis={} "
                            + "reason={} action=removed-from-capacity-accounting",
                    lease.getName(), lease.getReleaseAttempts(),
                    now - lease.getStateEnteredAtMillis(),
                    attemptsExceeded ? "attempts>=" + properties.getReclaimStuckAfterAttempts()
                            : "stuck>=" + properties.getReclaimStuckAfterMillis() + "ms");
        }
    }

    private void transition(Lease lease, LeaseState next, long now) {
        lease.setState(next);
        lease.setStateEnteredAtMillis(now);
    }

    private Object lockFor(String name) {
        return reclaimLocks.computeIfAbsent(name, k -> new Object());
    }

    private void persist() {
        snapshotStore.save(repository.findAll().stream().toList());
    }
}
