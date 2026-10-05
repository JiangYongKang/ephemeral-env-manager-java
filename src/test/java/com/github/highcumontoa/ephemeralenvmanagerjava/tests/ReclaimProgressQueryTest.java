package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.RenewRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimPhase;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimProgressView;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimQueryService;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.LocalFileSnapshotStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收进度只读查询测试：各阶段可见性、字段正确性、并发回收期间查询的只读性。
 */
class ReclaimProgressQueryTest {

    private static final long T0 = 5_000_000L;
    private static final long THRESHOLD = 5_000L;

    private record Harness(MutableClock clock, LeaseService service, LeaseRepository repository,
                           LocalResourceBackend backend, Reclaimer reclaimer,
                           ReclaimQueryService query) {
    }

    private Harness harness(Path snapshotFile) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(8);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(10_000_000);
        props.setReclaimStuckThresholdMillis(THRESHOLD);
        props.setSnapshotPath(snapshotFile.toString());
        MutableClock clock = new MutableClock(T0);
        LeaseRepository repository = new InMemoryLeaseRepository();
        LocalResourceBackend backend = new LocalResourceBackend(props);
        SnapshotStore store = new LocalFileSnapshotStore(props);
        Reclaimer reclaimer = new Reclaimer(repository, backend, store, props, clock);
        LeaseService service = new LeaseService(repository, backend, reclaimer, store, props, clock);
        return new Harness(clock, service, repository, backend, reclaimer,
                new ReclaimQueryService(repository, clock));
    }

    @Test
    void unfinishedLeases_areListedWithPhaseAttemptsReasonAndStuckDuration(@TempDir Path dir) {
        Harness h = harness(dir.resolve("snapshot.json"));

        // 不应出现在查询里：有效 ACTIVE 与已 RELEASED。
        h.service.acquire(new CreateRequest("env-active", "dave", 1_000_000));
        h.service.acquire(new CreateRequest("env-released", "erin", 10_000));
        h.service.release("env-released", null);
        // 兜底升级（QUARANTINED）：最早进入 RELEASING 且持续失败。
        h.service.acquire(new CreateRequest("env-stuck", "carol", 10_000));

        h.clock.advance(10_001); // env-stuck / env-released 的 10s 租约到期
        h.backend.injectDeallocateFailureAlways("env-stuck");
        h.reclaimer.reclaimExpired(); // env-stuck -> RELEASING attempts=1

        h.clock.advance(THRESHOLD + 1); // env-stuck 在 RELEASING 停留超过阈值
        // 等待回收（EXPIRED）与失败待重试（RELEASING, attempts>0）：较晚才到期。
        h.service.acquire(new CreateRequest("env-waiting", "alice", 20_000));
        h.service.acquire(new CreateRequest("env-retrying", "bob", 10_000));
        h.clock.advance(10_001); // env-retrying 到期（env-waiting 仍在租期内，扫描不动它）
        h.backend.injectDeallocateFailureAlways("env-retrying");
        h.reclaimer.reclaimExpired(); // env-retrying -> RELEASING attempts=1；env-stuck -> QUARANTINED
        h.clock.advance(10_001); // env-waiting 到期
        try {
            // 扫描之后、下轮扫描之前：续租竞争失败 -> EXPIRED，停留在等待回收阶段。
            h.service.renew("env-waiting", new RenewRequest("alice", 10_000));
        } catch (LeaseException expected) {
            // 续租竞争失败 -> EXPIRED
        }

        h.clock.advance(2_000); // 查询前统一再推进，停留时长可精确预期

        List<ReclaimProgressView> views = h.query().listUnfinished();
        Map<String, ReclaimProgressView> byName = views.stream()
                .collect(Collectors.toMap(ReclaimProgressView::name, Function.identity()));
        System.out.printf("[input] 5 个环境：waiting=EXPIRED, retrying=RELEASING(失败x1), stuck=QUARANTINED,"
                + " active=ACTIVE, released=RELEASED%n");
        views.forEach(v -> System.out.printf(
                "[judge] %-12s state=%-11s phase=%-15s attempts=%d stuck=%dms reason=%s%n",
                v.name(), v.state(), v.phase(), v.releaseAttempts(), v.stuckMillis(),
                v.lastFailureReason()));

        assertEquals(3, views.size(), "ACTIVE/RELEASED 不得出现在未回收干净列表");
        ReclaimProgressView waiting = byName.get("env-waiting");
        assertEquals(LeaseState.EXPIRED, waiting.state());
        assertEquals(ReclaimPhase.WAITING_RECLAIM, waiting.phase());
        assertEquals("alice", waiting.owner());
        assertEquals(0, waiting.releaseAttempts());

        ReclaimProgressView retrying = byName.get("env-retrying");
        assertEquals(LeaseState.RELEASING, retrying.state());
        assertEquals(ReclaimPhase.RETRY_PENDING, retrying.phase());
        assertEquals(1, retrying.releaseAttempts());
        assertNotNull(retrying.lastFailureReason(), "最近一次失败原因必须可见");
        assertTrue(retrying.lastFailureReason().contains("persistent"));

        ReclaimProgressView stuck = byName.get("env-stuck");
        assertEquals(LeaseState.QUARANTINED, stuck.state());
        assertEquals(ReclaimPhase.QUARANTINED, stuck.phase());
        assertEquals(2, stuck.releaseAttempts(), "升级当轮先重试一次再升级，次数停在此后不再增长");
        assertNotNull(stuck.lastFailureReason());

        // 停留时长可解释：retrying/stuck 在最后一次扫描时刻进入当前阶段（又经过 10_001+2_000），
        // waiting 在续租竞争失败时刻进入 EXPIRED（又经过 2_000）。
        assertEquals(2_000, waiting.stuckMillis(), "waiting 自 EXPIRED 起的停留时长");
        assertEquals(12_001, retrying.stuckMillis(), "retrying 自 RELEASING 起的停留时长");
        assertEquals(12_001, stuck.stuckMillis(), "stuck 自 QUARANTINED 起的停留时长");
    }

    @Test
    void concurrentReclaim_queriesAreConsistentAndReadOnly(@TempDir Path dir) throws Exception {
        Harness h = harness(dir.resolve("snapshot.json"));
        // 6 个环境：3 个一次性失败（首轮重试后成功），3 个直接成功。
        for (int i = 0; i < 6; i++) {
            h.service.acquire(new CreateRequest("env-" + i, "owner-" + i, 10_000));
        }
        h.clock.advance(10_001);
        for (int i = 0; i < 3; i++) {
            h.backend.injectDeallocateFailureOnce("env-" + i);
        }

        // 并发：2 个回收线程连续扫描，4 个查询线程高频查询。
        int queryThreads = 4;
        int queriesPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> queryErrors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> badViews = new ConcurrentLinkedQueue<>();
        try {
            for (int t = 0; t < 2; t++) {
                pool.submit(() -> {
                    await(start);
                    for (int i = 0; i < 20; i++) {
                        h.reclaimer.reclaimExpired();
                    }
                });
            }
            for (int t = 0; t < queryThreads; t++) {
                pool.submit(() -> {
                    await(start);
                    for (int i = 0; i < queriesPerThread; i++) {
                        try {
                            for (ReclaimProgressView v : h.query().listUnfinished()) {
                                if (v.phase() == null || v.state() == null
                                        || v.stuckMillis() < 0 || v.name() == null) {
                                    badViews.add("malformed view: " + v);
                                }
                            }
                        } catch (Throwable e) {
                            queryErrors.add(e);
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发回收+查询应在限时内完成");
        } finally {
            pool.shutdownNow();
        }
        System.out.printf("[input] 6 个到期环境(3 个首轮归还失败)，2 线程 x20 轮回收并发 %d 线程 x%d 次查询%n",
                queryThreads, queriesPerThread);
        System.out.printf("[judge] 查询异常数=%d, 非法视图数=%d%n", queryErrors.size(), badViews.size());
        assertEquals(0, queryErrors.size(), "并发回收期间查询不得抛异常");
        assertEquals(0, badViews.size(), "查询结果必须结构完整");

        // 回收最终全部完成（查询不干扰回收语义）。
        for (int i = 0; i < 6; i++) {
            assertEquals(LeaseState.RELEASED, h.repository.find("env-" + i).orElseThrow().getState(),
                    "env-" + i + " 应被回收完成");
        }
        assertEquals(0, h.backend.allocatedCount());
        assertEquals(0, h.query().listUnfinished().size(), "全部回收干净后查询应为空");

        // 只读性：系统静止后，密集查询前后全部租约字段逐位一致。
        Map<String, String> before = snapshotState(h.repository);
        for (int i = 0; i < 500; i++) {
            h.query().listUnfinished();
        }
        Map<String, String> after = snapshotState(h.repository);
        System.out.printf("[judge] 500 次查询前后租约状态一致=%s (leases=%d)%n",
                before.equals(after), before.size());
        assertEquals(before, after, "查询不得改变任何租约状态/进度");
    }

    private static Map<String, String> snapshotState(LeaseRepository repository) {
        Map<String, String> snap = new TreeMap<>();
        for (Lease l : repository.findAll()) {
            snap.put(l.getName(), l.getState() + "|" + l.getLeaseVersion() + "|"
                    + l.getReleaseAttempts() + "|" + l.getStateEnteredAtMillis() + "|"
                    + l.getExpiresAtMillis() + "|" + l.getLastFailureReason());
        }
        return snap;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
