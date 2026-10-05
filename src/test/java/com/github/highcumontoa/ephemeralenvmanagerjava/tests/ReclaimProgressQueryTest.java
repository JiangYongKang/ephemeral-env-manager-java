package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.RenewRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimProgress;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.LocalFileSnapshotStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收进度查询测试：并发回收场景下查询结果的正确性与只读性。
 */
class ReclaimProgressQueryTest {

    private static final long T0 = 5_000_000L;

    private record Harness(MutableClock clock, LeaseService service, LeaseRepository repository,
                           LocalResourceBackend backend, Reclaimer reclaimer) {
    }

    private Harness harness(Path snapshotFile, ResourceBackend backend, EphemeralProperties props) {
        MutableClock clock = new MutableClock(T0);
        LeaseRepository repository = new InMemoryLeaseRepository();
        SnapshotStore store = new LocalFileSnapshotStore(props);
        Reclaimer reclaimer = new Reclaimer(repository, backend, store, props, clock);
        LeaseService service = new LeaseService(repository, backend, reclaimer, store, props, clock);
        return new Harness(clock, service, repository,
                backend instanceof LocalResourceBackend l ? l : null, reclaimer);
    }

    private EphemeralProperties props(Path snapshotFile) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(8);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(1_000_000);
        props.setSnapshotPath(snapshotFile.toString());
        props.setReclaimStuckAfterAttempts(3);
        props.setReclaimStuckAfterMillis(60_000);
        return props;
    }

    @Test
    void progressQuery_coversAllUnreclaimedStages_withExplainableFields(@TempDir Path dir) {
        EphemeralProperties props = props(dir.resolve("s.json"));
        Harness h = harness(dir.resolve("s.json"), new LocalResourceBackend(props), props);

        // waiting：租期更长，扫描结束后才到期，保持“已到期未扫描”
        h.service.acquire(new CreateRequest("waiting", "alice", 20_000));
        // in-progress：归还失败过一次，停在 RELEASING 等待重试
        h.service.acquire(new CreateRequest("in-progress", "bob", 10_000));
        // stuck：失败次数达阈值，已升级 RECLAIM_STUCK
        h.service.acquire(new CreateRequest("stuck", "carol", 10_000));
        // healthy：有效租期内，不得出现在查询结果里
        h.service.acquire(new CreateRequest("healthy", "dave", 500_000));
        // done：已归还干净，不得出现
        h.service.acquire(new CreateRequest("done", "erin", 10_000));
        h.service.release("done", null);

        h.clock.advance(10_001); // in-progress/stuck 到期；waiting/healthy 未到期
        h.backend.injectDeallocateFailureOnce("in-progress");
        h.backend.injectDeallocateFailureAlways("stuck");
        h.reclaimer.reclaimExpired(); // in-progress 失败 1 次；stuck 失败 1 次
        h.reclaimer.reclaimOne("stuck"); // stuck 失败 2 次（定点重试，不影响 in-progress）
        h.reclaimer.reclaimOne("stuck"); // stuck 失败 3 次 -> 达到阈值升级

        h.clock.advance(10_000); // waiting 此时才到期且未被扫描；同时让“停留时长”可观测
        List<ReclaimProgress> progress = h.reclaimer.listPendingReclaims();
        Map<String, ReclaimProgress> byName = progress.stream()
                .collect(Collectors.toMap(ReclaimProgress::name, p -> p));

        System.out.printf("[input] waiting=扫描后才到期(未扫描), in-progress=失败1次, stuck=失败3次(阈值3), "
                + "healthy=有效租期, done=已释放; 扫描后又推进10000ms%n");
        progress.forEach(p -> System.out.printf(
                "[judge] entry name=%s owner=%s stage=%s attempts=%d lastError=%s millisInStage=%d%n",
                p.name(), p.owner(), p.stage(), p.releaseAttempts(),
                p.lastReclaimError(), p.millisInStage()));

        assertEquals(3, progress.size(), "等待回收/回收中/已升级三类都要查出，健康与已终结不得出现");
        // waiting：ACTIVE 已到期，等待回收
        ReclaimProgress waiting = byName.get("waiting");
        assertNotNull(waiting);
        assertEquals(LeaseState.ACTIVE, waiting.stage());
        assertEquals("alice", waiting.owner());
        assertEquals(0, waiting.releaseAttempts());
        assertNull(waiting.lastReclaimError());
        // in-progress：RELEASING，1 次失败，有失败原因
        ReclaimProgress inProgress = byName.get("in-progress");
        assertNotNull(inProgress);
        assertEquals(LeaseState.RELEASING, inProgress.stage());
        assertEquals(1, inProgress.releaseAttempts());
        assertNotNull(inProgress.lastReclaimError());
        assertTrue(inProgress.lastReclaimError().contains("injected"));
        // stuck：已升级 RECLAIM_STUCK，3 次失败
        ReclaimProgress stuck = byName.get("stuck");
        assertNotNull(stuck);
        assertEquals(LeaseState.RECLAIM_STUCK, stuck.stage());
        assertEquals(3, stuck.releaseAttempts());
        assertNotNull(stuck.lastReclaimError());
        // 停留时长：in-progress 在 10000ms 前进入 RELEASING
        assertTrue(inProgress.millisInStage() >= 10_000,
                "停留时长需覆盖扫描后推进的 10000ms，实际=" + inProgress.millisInStage());
        System.out.printf("[judge] healthy/done 未出现在结果中=%s%n",
                !byName.containsKey("healthy") && !byName.containsKey("done"));
    }

    @Test
    void progressQuery_isReadOnly_andDoesNotBlockReclaimOrWrites(@TempDir Path dir)
            throws Exception {
        EphemeralProperties props = props(dir.resolve("s.json"));
        LocalResourceBackend inner = new LocalResourceBackend(props);
        // 包装后端：对 blocked-env 的归还卡在闩锁上，模拟“回收卡住”
        CountDownLatch deallocateEntered = new CountDownLatch(1);
        CountDownLatch deallocateRelease = new CountDownLatch(1);
        ResourceBackend blocking = new ResourceBackend() {
            @Override
            public void allocate(String name) {
                inner.allocate(name);
            }

            @Override
            public void deallocate(String name) {
                if (name.equals("blocked-env")) {
                    deallocateEntered.countDown();
                    try {
                        deallocateRelease.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                inner.deallocate(name);
            }

            @Override
            public void releaseAccounting(String name) {
                inner.releaseAccounting(name);
            }

            @Override
            public int allocatedCount() {
                return inner.allocatedCount();
            }
        };
        Harness h = harness(dir.resolve("s.json"), blocking, props);

        h.service.acquire(new CreateRequest("blocked-env", "alice", 10_000));
        h.service.acquire(new CreateRequest("healthy", "bob", 500_000));
        h.clock.advance(10_001); // blocked-env 到期

        // 后台线程开始回收 blocked-env，并卡在归还上
        ExecutorService pool = Executors.newCachedThreadPool();
        Future<?> reclaimFuture = pool.submit(() -> h.reclaimer.reclaimOne("blocked-env"));
        assertTrue(deallocateEntered.await(5, TimeUnit.SECONDS), "回收线程应已进入归还调用");

        // 回收卡住期间：查询必须立即返回（不被按名锁/归还调用阻塞）
        long start = System.nanoTime();
        List<ReclaimProgress> duringReclaim = h.reclaimer.listPendingReclaims();
        long queryMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[input] blocked-env 回收卡在归还调用上; healthy 有效%n");
        System.out.printf("[judge] 卡住期间查询耗时=%dms, 结果=%s%n", queryMillis,
                duringReclaim.stream().map(p -> p.name() + ":" + p.stage()).toList());
        assertTrue(queryMillis < 2_000, "查询不得被卡住的回收阻塞，实际=" + queryMillis + "ms");
        assertEquals(1, duringReclaim.size());
        assertEquals("blocked-env", duringReclaim.get(0).name());
        assertEquals(LeaseState.RELEASING, duringReclaim.get(0).stage());

        // 回收卡住期间：正常创建与续租不被阻塞
        h.service.acquire(new CreateRequest("new-env", "carol", 10_000));
        h.service.renew("healthy", new RenewRequest("bob", 500_000));
        System.out.printf("[judge] 卡住期间创建 new-env=%s, 续租 healthy version=%d%n",
                h.repository.find("new-env").orElseThrow().getState(),
                h.repository.find("healthy").orElseThrow().getLeaseVersion());

        // 只读性：无任何其他活动时，连续查询不得改变任何租约状态
        Map<String, String> before = dump(h.repository);
        for (int i = 0; i < 100; i++) {
            h.reclaimer.listPendingReclaims();
        }
        Map<String, String> after = dump(h.repository);
        System.out.printf("[judge] 100 次连续查询前后状态一致=%s; before=%s%n", before.equals(after), before);
        assertEquals(before, after, "查询必须是只读的，不得改变任何状态");

        // 放开闩锁，卡住的回收完成
        deallocateRelease.countDown();
        reclaimFuture.get(5, TimeUnit.SECONDS);
        assertEquals(LeaseState.RELEASED, h.repository.find("blocked-env").orElseThrow().getState());
        System.out.printf("[judge] 闩锁放开后 blocked-env=%s%n",
                h.repository.find("blocked-env").orElseThrow().getState());
        pool.shutdownNow();
    }

    private Map<String, String> dump(LeaseRepository repository) {
        Map<String, String> dump = new TreeMap<>();
        for (Lease lease : repository.findAll()) {
            dump.put(lease.getName(), lease.getState() + "/" + lease.getReleaseAttempts()
                    + "/" + lease.getLeaseVersion() + "/" + lease.getExpiresAtMillis()
                    + "/" + lease.getLastReclaimError());
        }
        return dump;
    }
}
