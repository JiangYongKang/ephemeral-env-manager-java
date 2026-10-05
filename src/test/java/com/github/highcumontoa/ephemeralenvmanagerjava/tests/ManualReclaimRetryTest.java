package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.ReleaseRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimRetryOutcome;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimRetryResult;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.LocalFileSnapshotStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动回收重试测试：并发触发不重复归还、结果可区分、环境间互不干扰。
 */
class ManualReclaimRetryTest {

    private static final long T0 = 7_000_000L;

    private record Harness(MutableClock clock, LeaseService service, LeaseRepository repository,
                           LocalResourceBackend backend, Reclaimer reclaimer) {
    }

    private Harness harness(Path snapshotFile) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(8);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(10_000_000);
        props.setReclaimStuckThresholdMillis(60_000);
        props.setSnapshotPath(snapshotFile.toString());
        MutableClock clock = new MutableClock(T0);
        LeaseRepository repository = new InMemoryLeaseRepository();
        LocalResourceBackend backend = new LocalResourceBackend(props);
        SnapshotStore store = new LocalFileSnapshotStore(props);
        Reclaimer reclaimer = new Reclaimer(repository, backend, store, props, clock);
        LeaseService service = new LeaseService(repository, backend, reclaimer, store, props, clock);
        return new Harness(clock, service, repository, backend, reclaimer);
    }

    @Test
    void concurrentManualRetry_sameEnv_deallocatesExactlyOnce(@TempDir Path dir) throws Exception {
        Harness h = harness(dir.resolve("snapshot.json"));
        h.service.acquire(new CreateRequest("env-x", "alice", 10_000));
        h.backend.injectDeallocateFailureOnce("env-x");
        h.service.release("env-x", new ReleaseRequest("alice")); // 首次归还失败 -> RELEASING
        assertEquals(LeaseState.RELEASING, h.repository.find("env-x").orElseThrow().getState());
        int callsBefore = h.backend.deallocateCallCount("env-x");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReclaimRetryResult> results = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                await(start);
                results.add(h.reclaimer.retryOne("env-x"));
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Map<ReclaimRetryOutcome, Long> byOutcome = results.stream()
                .map(ReclaimRetryResult::outcome)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        int deallocateDelta = h.backend.deallocateCallCount("env-x") - callsBefore;
        System.out.printf("[input] env-x 回收失败挂起后，%d 线程并发手动重试%n", threads);
        System.out.printf("[judge] 结果分布=%s, 重试期间归还调用次数=%d, 最终状态=%s, 资源仍占用=%s%n",
                byOutcome, deallocateDelta,
                h.repository.find("env-x").orElseThrow().getState(), h.backend.isAllocated("env-x"));

        assertEquals(threads, results.size());
        assertEquals(1L, byOutcome.getOrDefault(ReclaimRetryOutcome.TRIGGERED, 0L),
                "恰好一个调用真正触发归还");
        assertEquals((long) threads - 1, byOutcome.getOrDefault(ReclaimRetryOutcome.ALREADY_RELEASED, 0L),
                "其余调用看到已归还干净，可区分且不产生副作用");
        assertEquals(1, deallocateDelta, "并发重试不得产生重复归还副作用");
        assertEquals(LeaseState.RELEASED, h.repository.find("env-x").orElseThrow().getState());
        assertFalse(h.backend.isAllocated("env-x"));
    }

    @Test
    void manualRetry_outcomesAreDistinguishable(@TempDir Path dir) {
        Harness h = harness(dir.resolve("snapshot.json"));
        h.service.acquire(new CreateRequest("env-active", "alice", 1_000_000));
        h.service.acquire(new CreateRequest("env-done", "bob", 10_000));
        h.service.release("env-done", new ReleaseRequest("bob")); // 直接归还成功

        ReclaimRetryResult notFound = h.reclaimer.retryOne("ghost");
        ReclaimRetryResult notDue = h.reclaimer.retryOne("env-active");
        ReclaimRetryResult alreadyReleased = h.reclaimer.retryOne("env-done");
        System.out.printf("[input] 分别重试：不存在的环境 / 有效租期内的环境 / 已归还干净的环境%n");
        System.out.printf("[judge] ghost=%s, env-active=%s(state=%s), env-done=%s(state=%s)%n",
                notFound.outcome(), notDue.outcome(), notDue.state(),
                alreadyReleased.outcome(), alreadyReleased.state());

        assertEquals(ReclaimRetryOutcome.NOT_FOUND, notFound.outcome());
        assertEquals(ReclaimRetryOutcome.NOT_DUE, notDue.outcome());
        assertEquals(LeaseState.ACTIVE, notDue.state());
        assertEquals(ReclaimRetryOutcome.ALREADY_RELEASED, alreadyReleased.outcome());
        // 有效租期内的环境绝不能被重试动作触碰。
        assertEquals(LeaseState.ACTIVE, h.repository.find("env-active").orElseThrow().getState());
        assertTrue(h.backend.isAllocated("env-active"), "有效环境的资源不得被动");
        assertEquals(0, h.backend.deallocateCallCount("env-active"), "NOT_DUE 不得触发归还调用");
        assertEquals(0, h.backend.deallocateCallCount("ghost"), "NOT_FOUND 不得触发归还调用");
    }

    @Test
    void concurrentManualRetry_differentEnvs_doNotInterfere(@TempDir Path dir) throws Exception {
        Harness h = harness(dir.resolve("snapshot.json"));
        // env-ok：故障已恢复，重试应成功；env-bad：持续故障，重试仍失败但互不影响。
        h.service.acquire(new CreateRequest("env-ok", "alice", 10_000));
        h.service.acquire(new CreateRequest("env-bad", "bob", 10_000));
        h.backend.injectDeallocateFailureOnce("env-ok");
        h.backend.injectDeallocateFailureAlways("env-bad");
        h.service.release("env-ok", new ReleaseRequest("alice"));
        h.service.release("env-bad", new ReleaseRequest("bob"));
        assertEquals(LeaseState.RELEASING, h.repository.find("env-ok").orElseThrow().getState());
        assertEquals(LeaseState.RELEASING, h.repository.find("env-bad").orElseThrow().getState());
        int badAttemptsBefore = h.repository.find("env-bad").orElseThrow().getReleaseAttempts();

        int threadsPerEnv = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadsPerEnv * 2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReclaimRetryResult> okResults = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ReclaimRetryResult> badResults = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threadsPerEnv; i++) {
            pool.submit(() -> {
                await(start);
                okResults.add(h.reclaimer.retryOne("env-ok"));
            });
            pool.submit(() -> {
                await(start);
                badResults.add(h.reclaimer.retryOne("env-bad"));
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long okTriggered = okResults.stream()
                .filter(r -> r.outcome() == ReclaimRetryOutcome.TRIGGERED).count();
        int badAttemptsAfter = h.repository.find("env-bad").orElseThrow().getReleaseAttempts();
        System.out.printf("[input] env-ok(故障已恢复)与env-bad(持续故障)各 %d 线程并发重试%n", threadsPerEnv);
        System.out.printf("[judge] env-ok: TRIGGERED=%d 状态=%s; env-bad: 全部TRIGGERED=%s 状态=%s attempts %d->%d%n",
                okTriggered, h.repository.find("env-ok").orElseThrow().getState(),
                badResults.stream().allMatch(r -> r.outcome() == ReclaimRetryOutcome.TRIGGERED),
                h.repository.find("env-bad").orElseThrow().getState(),
                badAttemptsBefore, badAttemptsAfter);

        assertEquals(1, okTriggered, "env-ok 恰好一次真正归还");
        assertEquals(LeaseState.RELEASED, h.repository.find("env-ok").orElseThrow().getState());
        assertEquals(LeaseState.RELEASING, h.repository.find("env-bad").orElseThrow().getState(),
                "env-bad 持续失败保持 RELEASING，不受 env-ok 成功影响");
        assertEquals(badAttemptsBefore + threadsPerEnv, badAttemptsAfter,
                "env-bad 的每次重试都独立计数，不丢不重");
        assertEquals(1, h.backend.allocatedCount(), "仅 env-bad 仍占用容量");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
