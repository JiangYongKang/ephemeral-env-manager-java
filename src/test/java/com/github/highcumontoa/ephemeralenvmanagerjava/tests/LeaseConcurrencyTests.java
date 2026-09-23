package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.CreateResult;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseStatus;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.ReclaimOutcome;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.RejectReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发同名创建 / 并发续租 / 到期与续租竞争：断言最终状态唯一、无重复占用、无丢失更新。
 */
class LeaseConcurrencyTests {

    @TempDir
    Path tmp;

    @Test
    void concurrentCreateSameNameProducesSingleOccupancy() throws Exception {
        var s = new LeaseTestSupport(tmp, 8, 10_000);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger idempotent = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String owner = (i == 0) ? "team-1" : "team-1"; // 全部同属主：验证幂等而非冲突
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    CreateResult r = s.service.create("hot-env", owner, 10_000L);
                    if (r.created()) {
                        created.incrementAndGet();
                    } else {
                        idempotent.incrementAndGet();
                    }
                } catch (LeaseException e) {
                    if (e.getReason() == RejectReason.NAME_CONFLICT) {
                        conflicts.incrementAndGet();
                    } else {
                        throw new RuntimeException(e);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        System.out.printf("[输入] %d 个线程同属主并发 create hot-env => 实际占用=%d, 幂等返回=%d, 冲突=%d%n",
                threads, created.get(), idempotent.get(), conflicts.get());
        assertEquals(1, created.get(), "并发同名创建必须只产生一次实际占用");
        assertEquals(threads - 1, idempotent.get(), "其余请求必须幂等命中，不能报错或重复占用");
        assertEquals(0, conflicts.get());
        assertEquals(1, s.store.count(l -> true), "存储中同名活跃租约只能有一条");
    }

    @Test
    void concurrentRenewalsDoNotLoseUpdates() throws Exception {
        var s = new LeaseTestSupport(tmp, 4, 10_000);
        Lease initial = s.service.create("renewed-env", "team-1", 100_000L).lease();
        int threads = 12;
        long ext = 1_000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    s.service.renew("renewed-env", "team-1", ext);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        Lease after = s.service.get("renewed-env");
        System.out.printf("[输入] %d 个线程并发续租各 +%dms => 全部成功=%s, deadline %d -> %d（差值=%d，期望=%d），version=%d%n",
                threads, ext, ok.get() == threads, initial.getDeadlineMillis(), after.getDeadlineMillis(),
                after.getDeadlineMillis() - initial.getDeadlineMillis(), threads * ext, after.getVersion());
        assertEquals(threads, ok.get());
        // 每次续租都在"当前 deadline"上顺延：12 次 +1000ms 一次都不能丢
        assertEquals(threads * ext, after.getDeadlineMillis() - initial.getDeadlineMillis(),
                "并发续租不得丢失更新");
        assertEquals(initial.getVersion() + threads, after.getVersion(), "每次续租版本号 +1");
    }

    @Test
    void renewVsExpiryRaceHasDeterministicOutcome() throws Exception {
        // deadline=1010000。一半线程在 now=1009999 续租，另一半推进到 1010001 触发到期回收；
        // 无论线程如何交错，最终环境要么仍是 ACTIVE（续租先生效），要么已 RELEASED（到期先生效），
        // 绝不能出现"续租成功但又被回收"之类的矛盾状态。
        var s = new LeaseTestSupport(tmp, 4, 10_000);
        // 预置到 deadline 前 1ms：续租线程不推进时钟直接续租；回收线程各推进 1ms 后回收。
        // 续租先入临界区 => deadline 顺延到 1012000，超过回收线程可达的最大时刻(1010009)，终态 ACTIVE；
        // 回收先入临界区 => now>=deadline 判定到期并释放，后续续租全部被拒。两个方向都是稳定终点。
        Lease initial = s.service.create("race-env", "team-1", 10_000L).lease();
        s.clock.advance(9_999);
        int pairs = 10;
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CyclicBarrier barrier = new CyclicBarrier(pairs * 2);
        AtomicInteger renewOk = new AtomicInteger();
        AtomicInteger renewRejectedExpired = new AtomicInteger();
        AtomicInteger reclaimed = new AtomicInteger();
        AtomicInteger skippedActive = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < pairs; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    try {
                        s.service.renew("race-env", "team-1", 2_000L);
                        renewOk.incrementAndGet();
                    } catch (LeaseException e) {
                        // LEASE_EXPIRED：临界区内判定已到期；ALREADY_RELEASED：竞争中已被回收
                        if (e.getReason() == RejectReason.LEASE_EXPIRED
                                || e.getReason() == RejectReason.ALREADY_RELEASED) {
                            renewRejectedExpired.incrementAndGet();
                        } else {
                            throw e;
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    // 每个回收线程只推进 1ms：是否越过 deadline(1010000) 取决于真实交错
                    s.clock.advance(1);
                    ReclaimOutcome o = s.service.reclaim("race-env");
                    if (o.released()) {
                        reclaimed.incrementAndGet();
                    } else if (o.detail().contains("有效租期")) {
                        skippedActive.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        boolean activeNow = s.store.find("race-env").isPresent();
        boolean releasedHistory = s.store.findReleased("race-env").isPresent();
        System.out.printf("[输入] 续租/到期竞争：续租成功=%d, 续租被拒(到期)=%d, 实际回收=%d, 回收时仍有效跳过=%d%n",
                renewOk.get(), renewRejectedExpired.get(), reclaimed.get(), skippedActive.get());
        System.out.printf("[断言] 最终状态唯一：活跃=%s, 已释放历史=%s（二者恰一为 true）%n",
                activeNow, releasedHistory);

        assertNotEquals(activeNow, releasedHistory, "最终状态必须唯一，不能既活跃又已释放");
        if (activeNow) {
            // 有续租先生效：若生效时刻未过期则仍 ACTIVE；且每次 +2000 全部生效或在到期前生效
            Lease lease = s.service.get("race-env");
            assertEquals(LeaseStatus.ACTIVE, lease.getStatus());
            assertTrue(renewOk.get() >= 1);
            assertEquals(0, reclaimed.get(), "ACTIVE 存续期间不得被回收");
        } else {
            // 到期先生效：所有续租都必须已被拒绝（LEASE_EXPIRED 或已释放后的 ALREADY_RELEASED），
            // 回收恰好一次，资源只归还一次
            assertEquals(pairs, renewRejectedExpired.get(),
                    "一旦到期判定生效，其余续租必须全部被拒绝");
            assertEquals(1, reclaimed.get(), "资源只能被归还一次");
        }
    }
}
