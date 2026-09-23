package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.RejectReason;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.ReleaseRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.RenewRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租约服务核心语义单元测试：并发同名创建/续租、拒绝原因可区分、续租与到期竞争。
 */
class LeaseServiceUnitTest {

    private static final long T0 = 1_000_000L;

    private record Harness(MutableClock clock, LeaseService service, LeaseRepository repository,
                           LocalResourceBackend backend, Reclaimer reclaimer) {
    }

    private Harness harness(int capacity) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(capacity);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(1_000_000);
        MutableClock clock = new MutableClock(T0);
        LeaseRepository repository = new InMemoryLeaseRepository();
        LocalResourceBackend backend = new LocalResourceBackend(props);
        SnapshotStore noopSnapshot = new SnapshotStore() {
            @Override
            public void save(List<Lease> leases) {
            }

            @Override
            public List<Lease> load() {
                return List.of();
            }
        };
        Reclaimer reclaimer = new Reclaimer(repository, backend, noopSnapshot, clock);
        LeaseService service = new LeaseService(repository, backend, reclaimer, noopSnapshot, props, clock);
        return new Harness(clock, service, repository, backend, reclaimer);
    }

    @Test
    void concurrentSameNameCreate_singleOccupancy_idempotent() throws Exception {
        Harness h = harness(8);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        ConcurrentLinkedQueue<String> owners = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Lease lease = h.service.acquire(new CreateRequest("env-A", "owner-A", 10_000));
                    if (lease.getState() == LeaseState.ACTIVE) {
                        success.incrementAndGet();
                    }
                    owners.add(lease.getOwner());
                } catch (Exception ignored) {
                    // 同归属方请求应全部幂等成功，不应出现异常
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        System.out.printf("[input] %d 个并发同名同归属方创建 env-A%n", threads);
        System.out.printf("[judge] 底层占用数=%d, 租约记录数=%d, 返回租约数=%d%n",
                h.backend.allocatedCount(), h.repository.findAll().size(), success.get());
        assertEquals(1, h.backend.allocatedCount(), "底层资源只能占用一次");
        assertEquals(1, h.repository.findAll().size(), "不得产生重复租约记录");
        assertEquals(threads, success.get(), "同归属方重复创建必须幂等成功");
        assertTrue(owners.stream().allMatch("owner-A"::equals));
    }

    @Test
    void concurrentSameNameCreate_differentOwner_nameConflict() throws Exception {
        Harness h = harness(8);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    try {
                        start.await();
                        h.service.acquire(new CreateRequest("env-B", "owner-" + idx, 10_000));
                        success.incrementAndGet();
                    } catch (Exception e) {
                        if (e instanceof LeaseException le && le.getReason() == RejectReason.NAME_CONFLICT) {
                            conflicts.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        System.out.printf("[input] %d 个并发同名不同归属方创建 env-B%n", threads);
        System.out.printf("[judge] 成功=%d, NAME_CONFLICT=%d, 总和=%d, 底层占用=%d%n",
                success.get(), conflicts.get(), success.get() + conflicts.get(), h.backend.allocatedCount());
        assertEquals(1, success.get(), "只有一个创建者获胜");
        assertEquals(threads - 1, conflicts.get(), "其余必须以 NAME_CONFLICT 被拒绝");
        assertEquals(1, h.backend.allocatedCount());
    }

    @Test
    void quotaExceeded_isDistinguishable() {
        Harness h = harness(2);
        h.service.acquire(new CreateRequest("e1", "o", 10_000));
        h.service.acquire(new CreateRequest("e2", "o", 10_000));
        LeaseException ex = assertThrows(LeaseException.class,
                () -> h.service.acquire(new CreateRequest("e3", "o", 10_000)));
        System.out.printf("[input] 容量=2, 连续创建 e1,e2,e3%n");
        System.out.printf("[judge] 第3次创建被拒绝 reason=%s message=%s%n",
                ex.getReason(), ex.getMessage());
        assertEquals(RejectReason.QUOTA_EXCEEDED, ex.getReason());
        assertEquals(2, h.backend.allocatedCount(), "容量不足时不得占用资源");
    }

    @Test
    void notOwner_andNotFound_andAlreadyReleased_distinguishable() {
        Harness h = harness(4);
        h.service.acquire(new CreateRequest("env", "alice", 10_000));

        LeaseException notOwner = assertThrows(LeaseException.class,
                () -> h.service.renew("env", new RenewRequest("bob", 10_000)));
        LeaseException notFound = assertThrows(LeaseException.class,
                () -> h.service.release("ghost", new ReleaseRequest("alice")));

        h.service.release("env", new ReleaseRequest("alice"));
        LeaseException alreadyReleased = assertThrows(LeaseException.class,
                () -> h.service.renew("env", new RenewRequest("alice", 10_000)));

        System.out.printf("[input] alice 占用 env；bob 续租；释放 ghost；释放 env 后再续租%n");
        System.out.printf("[judge] 非归属方=%s, 不存在=%s, 已释放=%s%n",
                notOwner.getReason(), notFound.getReason(), alreadyReleased.getReason());
        assertEquals(RejectReason.NOT_OWNER, notOwner.getReason());
        assertEquals(RejectReason.NOT_FOUND, notFound.getReason());
        assertEquals(RejectReason.ALREADY_RELEASED, alreadyReleased.getReason());
    }

    @Test
    void concurrentRenew_noLostUpdate_andExpiryRaceDeterministic() throws Exception {
        Harness h = harness(4);
        h.service.acquire(new CreateRequest("env", "alice", 10_000));

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    h.service.renew("env", new RenewRequest("alice", 10_000));
                    ok.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        Lease lease = h.repository.find("env").orElseThrow();
        System.out.printf("[input] %d 个并发续租(各 10000ms), 时钟固定 t=%d%n", threads, T0);
        System.out.printf("[judge] 成功续租次数=%d, 租约版本 version=%d, 期望到期=%d, 实际到期=%d%n",
                ok.get(), lease.getLeaseVersion(), T0 + 10_000, lease.getExpiresAtMillis());
        assertEquals(threads, ok.get());
        assertEquals(threads + 1, lease.getLeaseVersion(), "每次续租版本+1，不得丢失更新");
        // 时钟固定时并发续租最终到期时间确定
        assertEquals(T0 + 10_000, lease.getExpiresAtMillis());

        // 到期竞争：时钟越过到期点后续租必须被拒绝，且租约转为 EXPIRED
        h.clock.set(T0 + 10_001);
        LeaseException expired = assertThrows(LeaseException.class,
                () -> h.service.renew("env", new RenewRequest("alice", 10_000)));
        System.out.printf("[input] 时钟推进到 %d（到期点 %d 之后）后续租%n", T0 + 10_001, T0 + 10_000);
        System.out.printf("[judge] 到期续租被拒绝 reason=%s, 租约状态=%s%n",
                expired.getReason(), lease.getState());
        assertEquals(RejectReason.NOT_ACTIVE, expired.getReason());
        assertEquals(LeaseState.EXPIRED, h.repository.find("env").orElseThrow().getState());
    }

    @Test
    void clockAnomaly_jumpBackward_leaseStillValidAndNotReclaimed() {
        Harness h = harness(4);
        h.service.acquire(new CreateRequest("env", "alice", 10_000));
        h.clock.set(T0 + 5_000);
        h.reclaimer.reclaimExpired();
        assertNotNull(h.repository.find("env").orElse(null));
        assertEquals(LeaseState.ACTIVE, h.repository.find("env").orElseThrow().getState());
        // 时钟异常回拨到更早：仍在有效期内，不得误回收
        h.clock.set(T0 - 999_999);
        int reclaimed = h.reclaimer.reclaimExpired();
        System.out.printf("[input] 时钟从 t=%d 异常回拨到 t=%d%n", T0 + 5_000, T0 - 999_999);
        System.out.printf("[judge] 回拨后回收数=%d, 状态=%s, 仍占用=%s%n",
                reclaimed, h.repository.find("env").orElseThrow().getState(),
                h.backend.isAllocated("env"));
        assertEquals(0, reclaimed, "有效租期内的环境不得被误回收");
        assertEquals(LeaseState.ACTIVE, h.repository.find("env").orElseThrow().getState());
    }

    @Test
    void releasedNameRebuild_andConcurrentMixedRequests_noLeak() throws Exception {
        Harness h = harness(8);
        h.service.acquire(new CreateRequest("env-R", "alice", 10_000));
        h.service.release("env-R", new ReleaseRequest("alice"));
        assertEquals(LeaseState.RELEASED, h.repository.find("env-R").orElseThrow().getState());

        // 同 owner 在 RELEASED 后重建，得到全新 ACTIVE 租约
        Lease rebuilt = h.service.acquire(new CreateRequest("env-R", "alice", 10_000));
        System.out.printf("[input] 释放后同 owner 重建 env-R%n");
        System.out.printf("[judge] 重建后状态=%s version=%d 占用=%s%n",
                rebuilt.getState(), rebuilt.getLeaseVersion(), h.backend.isAllocated("env-R"));
        assertEquals(LeaseState.ACTIVE, rebuilt.getState());
        assertEquals(1, rebuilt.getLeaseVersion());

        // 再释放，然后多 owner 并发重建：只能有一个获胜，后端占用恰好 1
        h.service.release("env-R", new ReleaseRequest("alice"));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    try {
                        start.await();
                        Lease lease = h.service.acquire(
                                new CreateRequest("env-R", "owner-" + idx, 10_000));
                        if (lease.getState() == LeaseState.ACTIVE && lease.getLeaseVersion() == 1) {
                            wins.incrementAndGet();
                        }
                    } catch (LeaseException e) {
                        if (e.getReason() == RejectReason.NAME_CONFLICT) {
                            conflicts.incrementAndGet();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        System.out.printf("[input] %d 个不同 owner 并发重建已释放的 env-R%n", threads);
        System.out.printf("[judge] 获胜=%d 冲突=%d 后端占用数=%d 租约记录数=%d%n",
                wins.get(), conflicts.get(), h.backend.allocatedCount(), h.repository.findAll().size());
        assertEquals(1, wins.get(), "只有一个重建者获胜");
        assertEquals(threads - 1, conflicts.get(), "其余全部 NAME_CONFLICT");
        assertEquals(1, h.backend.allocatedCount(), "失败路径不得残留或误删资源");
    }
}
