package com.github.highcumontoa.ephemeralenvmanagerjava.lease;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Clock;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.RejectReason;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ResourceException;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 租约编排服务：创建、续租、释放与状态查询。
 *
 * 并发语义：
 * - 并发同名创建：名称判定、资源占用、映射写入在同一名称级锁内原子完成；
 * - 并发续租与到期竞争：续租判定（ACTIVE 且未到期）在 bin 锁内原子完成；
 * - 时钟判定：仅当 expiresAtMillis > now 时视为有效租期。
 */
@Service
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final LeaseRepository repository;
    private final ResourceBackend backend;
    private final Reclaimer reclaimer;
    private final SnapshotStore snapshotStore;
    private final EphemeralProperties properties;
    private final Clock clock;

    public LeaseService(LeaseRepository repository, ResourceBackend backend, Reclaimer reclaimer,
                        SnapshotStore snapshotStore, EphemeralProperties properties, Clock clock) {
        this.repository = repository;
        this.backend = backend;
        this.reclaimer = reclaimer;
        this.snapshotStore = snapshotStore;
        this.properties = properties;
        this.clock = clock;
    }

    public Lease acquire(CreateRequest request) {
        long ttl = normalizeTtl(request.ttlMillis());
        long now = clock.nowMillis();
        // 名称判定、容量占用、映射写入在同一个名称级锁内完成：
        // - 并发同名同归属方：幂等返回同一租约，资源只占用一次；
        // - 并发同名不同归属方（含 RELEASED 重建竞争）：仅获胜者占用资源，无泄漏；
        // - 容量不足：在写映射前以 QUOTA_EXCEEDED 拒绝，不残留任何占用。
        Lease result = repository.create(request.name(), existing -> {
            if (existing.isPresent()) {
                Lease current = existing.get();
                if (current.getState() != LeaseState.RELEASED
                        && current.getOwner().equals(request.owner())) {
                    // 对仍占有的重复请求幂等返回，不重复占用。
                    return current;
                }
                if (current.getState() == LeaseState.RELEASED) {
                    // 名称已终结（无论归属方）：重建为新租约。占用成功才写入，失败映射不变。
                    allocateOrQuotaFailure(request.name(), request.owner());
                    return newLease(request.name(), request.owner(), now, ttl);
                }
                throw conflict(current);
            }
            allocateOrQuotaFailure(request.name(), request.owner());
            return newLease(request.name(), request.owner(), now, ttl);
        });
        persist();
        log.info("acquire settled name={} owner={} state={} version={}",
                result.getName(), result.getOwner(), result.getState(), result.getLeaseVersion());
        return result;
    }

    public Lease renew(String name, RenewRequest request) {
        long ttl = normalizeTtl(request.ttlMillis());
        long now = clock.nowMillis();
        Lease updated = repository.mutate(name, lease -> {
            if (lease.getState() == LeaseState.RELEASED) {
                throw new LeaseException(RejectReason.ALREADY_RELEASED,
                        "lease already released: " + name);
            }
            if (lease.getState() != LeaseState.ACTIVE) {
                throw new LeaseException(RejectReason.NOT_ACTIVE,
                        "lease not active (state=" + lease.getState() + "): " + name);
            }
            if (!lease.getOwner().equals(request.owner())) {
                throw new LeaseException(RejectReason.NOT_OWNER,
                        "owner mismatch: lease owned by " + lease.getOwner()
                                + ", requested by " + request.owner());
            }
            if (lease.getExpiresAtMillis() <= now) {
                // 到期与续租竞争：以时钟判定为准，已到期则续租被拒绝，等待回收。
                lease.setState(LeaseState.EXPIRED);
                throw new LeaseException(RejectReason.NOT_ACTIVE,
                        "lease expired at " + lease.getExpiresAtMillis()
                                + ", now=" + now + ": " + name);
            }
            // 在锁内完成读-改-写，并发续租不会丢失更新。
            lease.setExpiresAtMillis(now + ttl);
            lease.setLeaseVersion(lease.getLeaseVersion() + 1);
            return lease;
        });
        if (updated == null) {
            throw new LeaseException(RejectReason.NOT_FOUND, "lease not found: " + name);
        }
        persist();
        log.info("renew ok name={} owner={} ttlMillis={} newExpiresAt={} version={}",
                name, request.owner(), ttl, updated.getExpiresAtMillis(), updated.getLeaseVersion());
        return updated;
    }

    public Lease release(String name, ReleaseRequest request) {
        Lease updated = repository.mutate(name, lease -> {
            if (lease.getState() == LeaseState.RELEASED) {
                // 重复释放幂等返回，不产生副作用。
                return lease;
            }
            if (request != null && request.owner() != null
                    && !lease.getOwner().equals(request.owner())) {
                throw new LeaseException(RejectReason.NOT_OWNER,
                        "owner mismatch: lease owned by " + lease.getOwner()
                                + ", requested by " + request.owner());
            }
            lease.setState(LeaseState.RELEASING);
            return lease;
        });
        if (updated == null) {
            throw new LeaseException(RejectReason.NOT_FOUND, "lease not found: " + name);
        }
        persist();
        // 释放后立即触发归还；失败不抛出，由后台扫描重试兜底。
        reclaimer.reclaimOne(name);
        persist();
        Lease result = repository.find(name).orElse(updated);
        log.info("release settled name={} state={}", name, result.getState());
        return result;
    }

    public Optional<Lease> get(String name) {
        return repository.find(name);
    }

    public List<Lease> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(Lease::getName))
                .toList();
    }

    private void allocateOrQuotaFailure(String name, String owner) {
        try {
            backend.allocate(name);
        } catch (ResourceException e) {
            log.info("acquire rejected name={} owner={} reason=QUOTA_EXCEEDED detail={}",
                    name, owner, e.getMessage());
            throw new LeaseException(RejectReason.QUOTA_EXCEEDED, e.getMessage());
        }
    }

    private Lease newLease(String name, String owner, long now, long ttl) {
        Lease lease = new Lease();
        lease.setName(name);
        lease.setOwner(owner);
        lease.setState(LeaseState.ACTIVE);
        lease.setCreatedAtMillis(now);
        lease.setExpiresAtMillis(now + ttl);
        lease.setLeaseVersion(1);
        lease.setReleaseAttempts(0);
        return lease;
    }

    private long normalizeTtl(long ttlMillis) {
        long ttl = ttlMillis > 0 ? ttlMillis : properties.getDefaultTtlMillis();
        if (ttl > properties.getMaxTtlMillis()) {
            throw new LeaseException(RejectReason.INVALID_TTL,
                    "ttlMillis=" + ttl + " exceeds maxTtlMillis=" + properties.getMaxTtlMillis());
        }
        return ttl;
    }

    private LeaseException conflict(Lease current) {
        log.info("acquire rejected name={} owner={} reason=NAME_CONFLICT state={} ownerOfLease={}",
                current.getName(), current.getOwner(), current.getState(), current.getOwner());
        return new LeaseException(RejectReason.NAME_CONFLICT,
                "name '" + current.getName() + "' is held by another owner in state "
                        + current.getState());
    }

    private void persist() {
        snapshotStore.save(repository.findAll().stream().toList());
    }
}
