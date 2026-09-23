package com.github.highcumontoa.ephemeralenvmanagerjava.service;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.CreateResult;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseStatus;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.ReclaimOutcome;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.ReleasedRecord;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.RejectReason;
import com.github.highcumontoa.ephemeralenvmanagerjava.resource.ResourceReclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.store.LeaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 临时环境租约与回收编排核心服务。
 *
 * <p>关键语义：</p>
 * <ul>
 *   <li>同名并发安全：create/renew/release/reclaim 的全部"检查-判定-写入"都在
 *       {@link LeaseStore#mutate} 的同一原子区间内完成，结论唯一；</li>
 *   <li>幂等：同名同属主重复创建直接返回既有占用；已归还资源重复回收直接跳过；</li>
 *   <li>续租与到期竞争：以原子区间内 now 与 deadline 的比较为准，续租成功则 deadline 只增不减，
 *       时钟回拨也不会缩短既有租期；</li>
 *   <li>失败可区分：每类拒绝映射到不同 {@link RejectReason}；</li>
 *   <li>失败隔离：单个环境回收失败仅记录到该租约（RECLAIM_FAILED），不抛出、不阻塞其他环境。</li>
 * </ul>
 */
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final LeaseStore store;
    private final ResourceReclaimer reclaimer;
    private final Clock clock;
    private final int maxActiveLeases;
    private final long defaultTtlMillis;

    public LeaseService(LeaseStore store, ResourceReclaimer reclaimer, Clock clock,
                        int maxActiveLeases, long defaultTtlMillis) {
        this.store = store;
        this.reclaimer = reclaimer;
        this.clock = clock;
        this.maxActiveLeases = maxActiveLeases;
        this.defaultTtlMillis = defaultTtlMillis;
    }

    private long now() {
        return clock.millis();
    }

    private static boolean isExpired(Lease lease, long now) {
        return lease.getStatus() == LeaseStatus.ACTIVE && lease.getDeadlineMillis() <= now;
    }

    /** 创建（占用）临时环境；同名同属主重复创建幂等返回，同名不同属主拒绝。 */
    public CreateResult create(String name, String owner, Long ttlMillis) {
        validateName(name);
        validateOwner(owner);
        long ttl = ttlMillis == null ? defaultTtlMillis : ttlMillis;
        if (ttl <= 0) {
            throw new LeaseException(RejectReason.INVALID_REQUEST, "ttlMillis 必须为正数: " + ttl);
        }
        long ts = now();
        AtomicReference<RejectReason> reject = new AtomicReference<>();
        AtomicReference<String> rejectMsg = new AtomicReference<>();
        AtomicReference<Boolean> createdRef = new AtomicReference<>(false);

        Lease saved = store.mutate(name, existing -> {
            if (existing.isPresent()) {
                Lease l = existing.get();
                if (!owner.equals(l.getOwner())) {
                    reject.set(RejectReason.NAME_CONFLICT);
                    rejectMsg.set("环境 '" + name + "' 已被属主 '" + l.getOwner() + "' 占用");
                    return null;
                }
                // 同名同属主：保持幂等，不重建、不重置租期、不重复占用容量
                return l;
            }
            // 容量按"仍占资源的未终结租约"计数；已到期但尚未完成回收的环境仍占容量，
            // 这样回收失败/卡住时不会超额占用真实资源
            int occupied = store.count(l -> l.getStatus() != LeaseStatus.RELEASED);
            if (occupied >= maxActiveLeases) {
                reject.set(RejectReason.QUOTA_EXCEEDED);
                rejectMsg.set("容量不足：配额 " + maxActiveLeases + "，当前占用 " + occupied);
                return null;
            }
            Lease l = new Lease();
            l.setName(name);
            l.setOwner(owner);
            l.setResourceId("res-" + name + "-" + UUID.randomUUID());
            l.setCreatedAtMillis(ts);
            l.setUpdatedAtMillis(ts);
            l.setDeadlineMillis(ts + ttl);
            l.setStatus(LeaseStatus.ACTIVE);
            l.setVersion(1);
            l.setReclaimAttempts(0);
            createdRef.set(true);
            return l;
        });

        if (reject.get() != null) {
            log.info("[create] name={} owner={} ttl={}ms 被拒绝: {} ({})", name, owner, ttl,
                    reject.get(), rejectMsg.get());
            throw new LeaseException(reject.get(), rejectMsg.get());
        }

        boolean created = createdRef.get();
        String detail;
        if (created) {
            detail = "已占用，状态=ACTIVE，deadline=" + saved.getDeadlineMillis();
            log.info("[create] name={} owner={} ttl={}ms 创建成功 resource={} deadline={}",
                    name, owner, ttl, saved.getResourceId(), saved.getDeadlineMillis());
        } else {
            detail = "幂等命中：同名环境已存在且属主一致，状态=" + saved.getStatus()
                    + "，deadline=" + saved.getDeadlineMillis();
            log.info("[create] name={} owner={} 幂等返回既有租约 status={} version={}",
                    name, owner, saved.getStatus(), saved.getVersion());
        }
        return new CreateResult(saved, created, detail);
    }

    /** 续租：仅 ACTIVE 且未过期可续租；非归属方/已释放/已到期均给出可区分拒绝。 */
    public Lease renew(String name, String owner, Long extendMillis) {
        validateName(name);
        validateOwner(owner);
        long ext = extendMillis == null ? defaultTtlMillis : extendMillis;
        if (ext <= 0) {
            throw new LeaseException(RejectReason.INVALID_REQUEST, "extendMillis 必须为正数: " + ext);
        }
        long ts = now();
        AtomicReference<RejectReason> reject = new AtomicReference<>();
        AtomicReference<String> rejectMsg = new AtomicReference<>();

        Lease saved = store.mutate(name, existing -> {
            if (existing.isEmpty()) {
                if (store.findReleased(name).isPresent()) {
                    reject.set(RejectReason.ALREADY_RELEASED);
                    rejectMsg.set("环境 '" + name + "' 已释放，不能续租");
                } else {
                    reject.set(RejectReason.NOT_FOUND);
                    rejectMsg.set("环境 '" + name + "' 不存在");
                }
                return null;
            }
            Lease l = existing.get();
            if (!owner.equals(l.getOwner())) {
                reject.set(RejectReason.NOT_OWNER);
                rejectMsg.set("非归属方：环境 '" + name + "' 属于 '" + l.getOwner() + "'，操作方为 '" + owner + "'");
                return null;
            }
            if (l.getStatus() != LeaseStatus.ACTIVE) {
                reject.set(RejectReason.LEASE_EXPIRED);
                rejectMsg.set("环境 '" + name + "' 当前状态为 " + l.getStatus()
                        + "，已进入到期回收流程，不能续租（到期与续租竞争的确定结论）");
                return null;
            }
            if (l.getDeadlineMillis() <= ts) {
                // 原子区间内判定到期：续租与回收竞争时，谁先在临界区内判定，结论以谁为准且唯一
                l.setStatus(LeaseStatus.EXPIRED);
                l.setUpdatedAtMillis(ts);
                reject.set(RejectReason.LEASE_EXPIRED);
                rejectMsg.set("环境 '" + name + "' 已到期（now=" + ts + ", deadline="
                        + l.getDeadlineMillis() + "），续租被拒绝并标记 EXPIRED 等待回收");
                return l;
            }
            long oldDeadline = l.getDeadlineMillis();
            // 以当前到期时刻为基准顺延；时钟回拨时基准取 max，保证租期只增不减
            long base = Math.max(oldDeadline, ts);
            l.setDeadlineMillis(base + ext);
            l.setUpdatedAtMillis(ts);
            l.setVersion(l.getVersion() + 1);
            return l;
        });

        if (reject.get() != null) {
            log.info("[renew] name={} owner={} extend={}ms 被拒绝: {} ({})", name, owner, ext,
                    reject.get(), rejectMsg.get());
            throw new LeaseException(reject.get(), rejectMsg.get());
        }
        log.info("[renew] name={} owner={} extend={}ms 续租成功 {} -> {} version={}",
                name, owner, ext, saved.getDeadlineMillis() - ext, saved.getDeadlineMillis(),
                saved.getVersion());
        return saved;
    }

    /** 主动释放：仅归属方可操作；底层资源归还幂等、失败可重试。 */
    public Lease release(String name, String owner) {
        validateName(name);
        validateOwner(owner);
        long ts = now();
        AtomicReference<RejectReason> reject = new AtomicReference<>();
        AtomicReference<String> rejectMsg = new AtomicReference<>();
        AtomicReference<Lease> releasedView = new AtomicReference<>();

        store.mutate(name, existing -> {
            if (existing.isEmpty()) {
                if (store.findReleased(name).isPresent()) {
                    reject.set(RejectReason.ALREADY_RELEASED);
                    rejectMsg.set("环境 '" + name + "' 已释放，重复释放被拒绝");
                } else {
                    reject.set(RejectReason.NOT_FOUND);
                    rejectMsg.set("环境 '" + name + "' 不存在");
                }
                return null;
            }
            Lease l = existing.get();
            if (!owner.equals(l.getOwner())) {
                reject.set(RejectReason.NOT_OWNER);
                rejectMsg.set("非归属方：环境 '" + name + "' 属于 '" + l.getOwner() + "'，操作方为 '" + owner + "'");
                return null;
            }
            // 进入回收：归还资源与状态推进在同一原子区间，回收与续租并发不会产生矛盾结论
            l.setStatus(LeaseStatus.RECLAIMING);
            l.setUpdatedAtMillis(ts);
            l.setVersion(l.getVersion() + 1);
            try {
                reclaimer.release(l.getResourceId());
            } catch (Exception e) {
                l.setStatus(LeaseStatus.RECLAIM_FAILED);
                l.setReclaimAttempts(l.getReclaimAttempts() + 1);
                l.setLastErrorMessage(e.getMessage());
                // 主动释放遇到资源归还失败：作为可区分拒绝返回，租约进入失败队列等待后台/手动重试
                reject.set(RejectReason.RECLAIM_FAILED);
                rejectMsg.set("释放 '" + name + "' 时资源归还失败，已记录待重试: " + e.getMessage());
                return l;
            }
            ReleasedRecord record = new ReleasedRecord();
            record.setName(name);
            record.setOwner(l.getOwner());
            record.setReleasedAtMillis(ts);
            record.setReason("MANUAL_RELEASE");
            store.addReleased(record);
            // 终结前构造对外视图（状态记为 RELEASED），随后从活跃表移除
            Lease view = new Lease();
            view.setName(l.getName());
            view.setOwner(l.getOwner());
            view.setResourceId(l.getResourceId());
            view.setCreatedAtMillis(l.getCreatedAtMillis());
            view.setDeadlineMillis(l.getDeadlineMillis());
            view.setUpdatedAtMillis(ts);
            view.setStatus(LeaseStatus.RELEASED);
            view.setVersion(l.getVersion() + 1);
            view.setReclaimAttempts(l.getReclaimAttempts());
            releasedView.set(view);
            return LeaseStore.REMOVE;
        });

        if (reject.get() != null) {
            log.info("[release] name={} owner={} 被拒绝: {} ({})", name, owner, reject.get(), rejectMsg.get());
            throw new LeaseException(reject.get(), rejectMsg.get());
        }
        log.info("[release] name={} owner={} 主动释放成功，资源已归还", name, owner);
        return releasedView.get();
    }

    /** 查询单个环境的当前状态；不存在或已释放分别给出可区分拒绝。 */
    public Lease get(String name) {
        validateName(name);
        Optional<Lease> l = store.find(name);
        if (l.isPresent()) {
            return l.get();
        }
        if (store.findReleased(name).isPresent()) {
            throw new LeaseException(RejectReason.ALREADY_RELEASED, "环境 '" + name + "' 已释放");
        }
        throw new LeaseException(RejectReason.NOT_FOUND, "环境 '" + name + "' 不存在");
    }

    /**
     * 回收一个到期/正在回收/回收失败的环境。可重复执行而不产生副作用；
     * 单次回收失败只记录该环境的失败原因，不抛出，避免阻塞其他环境。
     */
    public ReclaimOutcome reclaim(String name) {
        validateName(name);
        long ts = now();

        Optional<Lease> found = store.find(name);
        if (found.isEmpty()) {
            Optional<ReleasedRecord> rec = store.findReleased(name);
            if (rec.isPresent()) {
                String detail = "环境已释放（" + rec.get().getReason()
                        + "），重复回收为空操作，资源不会重复归还";
                log.info("[reclaim] name={} 幂等跳过：{}", name, detail);
                return new ReclaimOutcome(name, false, detail);
            }
            String detail = "环境 '" + name + "' 不存在，无任何回收动作";
            log.info("[reclaim] name={} {}", name, detail);
            return new ReclaimOutcome(name, false, detail);
        }

        Lease lease = found.get();
        // 仍在有效租期内（ACTIVE 且 deadline 未到）：绝不能误回收。
        // 注意 ACTIVE 但 deadline 已过的租约不算受保护——它是到期环境，必须继续向下回收。
        if (lease.getStatus() == LeaseStatus.ACTIVE && lease.getDeadlineMillis() > ts) {
            String detail = "环境仍在有效租期内（deadline=" + lease.getDeadlineMillis() + " > now=" + ts
                    + "），拒绝回收";
            log.info("[reclaim] name={} {}", name, detail);
            return new ReclaimOutcome(name, false, detail);
        }

        AtomicReference<ReclaimOutcome> failure = new AtomicReference<>();
        AtomicReference<String> successResource = new AtomicReference<>();
        Lease saved = store.mutate(name, existing -> {
            if (existing.isEmpty()) {
                // 极小窗口内被其他线程终结：由 released 历史解释，保持幂等
                return null;
            }
            Lease l = existing.get();
            if (l.getStatus() == LeaseStatus.ACTIVE) {
                if (l.getDeadlineMillis() > ts) {
                    return null; // 双重检查，防止与续租竞争时误回收
                }
                l.setStatus(LeaseStatus.EXPIRED);
            }
            // EXPIRED / RECLAIM_FAILED / RECLAIMING（上次崩溃残留）都在此处尝试归还；
            // 归还器自身幂等，重复执行不会重复归还
            l.setStatus(LeaseStatus.RECLAIMING);
            l.setUpdatedAtMillis(ts);
            l.setVersion(l.getVersion() + 1);
            try {
                reclaimer.release(l.getResourceId());
            } catch (Exception e) {
                l.setStatus(LeaseStatus.RECLAIM_FAILED);
                l.setReclaimAttempts(l.getReclaimAttempts() + 1);
                l.setLastErrorMessage(e.getMessage());
                failure.set(new ReclaimOutcome(name, false,
                        "回收失败（第 " + l.getReclaimAttempts() + " 次），已记录原因并保留待重试: "
                                + e.getMessage()));
                return l;
            }
            ReleasedRecord record = new ReleasedRecord();
            record.setName(name);
            record.setOwner(l.getOwner());
            record.setReleasedAtMillis(ts);
            record.setReason("LEASE_EXPIRED");
            store.addReleased(record);
            successResource.set(l.getResourceId());
            return LeaseStore.REMOVE;
        });

        if (failure.get() != null) {
            log.warn("[reclaim] name={} {}", name, failure.get().detail());
            return failure.get();
        }
        if (successResource.get() != null) {
            String detail = "到期回收成功：resource=" + successResource.get()
                    + " 已归还，状态终结为 RELEASED";
            log.info("[reclaim] name={} {}", name, detail);
            return new ReclaimOutcome(name, true, detail);
        }
        if (saved == null) {
            boolean wasReleased = store.findReleased(name).isPresent();
            String detail = wasReleased
                    ? "回收竞态期间环境已被释放，本次为空操作（幂等）"
                    : "环境仍在有效租期内或不存在，未回收";
            return new ReclaimOutcome(name, false, detail);
        }
        // 走到这里说明临界区内未到期而原样返回（与外层保护呼应），不得回收
        return new ReclaimOutcome(name, false,
                "临界区内复核仍在有效租期内（deadline=" + saved.getDeadlineMillis()
                        + "），未回收");
    }

    /** 扫描全部到期环境并逐个回收（单个失败不影响其余），返回每个环境的判定结果。 */
    public List<ReclaimOutcome> reclaimExpired() {
        long ts = now();
        // 先快照：候选为已到期（ACTIVE 但 deadline 已过）或处于回收流程中的租约
        List<Lease> candidates = store.snapshot(l ->
                l.getStatus() != LeaseStatus.RELEASED
                        && (l.getStatus() != LeaseStatus.ACTIVE || l.getDeadlineMillis() <= ts));
        List<ReclaimOutcome> outcomes = new ArrayList<>();
        log.info("[reclaim-scan] now={} 候选到期/待回收环境 {} 个: {}", ts, candidates.size(),
                candidates.stream().map(l -> l.getName() + "(" + l.getStatus() + ")").toList());
        for (Lease candidate : candidates) {
            try {
                outcomes.add(reclaim(candidate.getName()));
            } catch (Exception e) {
                // 任何意外都被限制在单个环境内，绝不阻塞其他环境的创建与回收
                log.error("[reclaim-scan] name={} 回收时出现未预期异常，已隔离: {}",
                        candidate.getName(), e.toString());
                outcomes.add(new ReclaimOutcome(candidate.getName(), false,
                        "回收时出现未预期异常，已隔离不影响其他环境: " + e.getMessage()));
            }
        }
        return outcomes;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new LeaseException(RejectReason.INVALID_REQUEST, "环境名称不能为空");
        }
    }

    private static void validateOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new LeaseException(RejectReason.INVALID_REQUEST, "属主标识不能为空");
        }
    }
}
