package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.ReleaseRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ManualRetryResult;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimRetryOutcome;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.LocalFileSnapshotStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动回收重试测试：结果可区分、同一环境并发触发不产生重复归还、不干扰其他环境。
 */
class ManualReclaimRetryTest {

    private static final long T0 = 5_000_000L;

    /** 统计每个环境实际执行归还调用的次数。 */
    private static final class CountingBackend extends LocalResourceBackend {
        private final Map<String, AtomicInteger> deallocateCalls = new ConcurrentHashMap<>();

        CountingBackend(EphemeralProperties props) {
            super(props);
        }

        @Override
        public void deallocate(String name) {
            deallocateCalls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
            super.deallocate(name);
        }

        int deallocateCalls(String name) {
            return deallocateCalls.getOrDefault(name, new AtomicInteger()).get();
        }
    }

    private record Harness(MutableClock clock, LeaseService service, LeaseRepository repository,
                           CountingBackend backend, Reclaimer reclaimer) {
    }

    private Harness harness(Path snapshotFile) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(8);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(1_000_000);
        props.setSnapshotPath(snapshotFile.toString());
        MutableClock clock = new MutableClock(T0);
        LeaseRepository repository = new InMemoryLeaseRepository();
        CountingBackend backend = new CountingBackend(props);
        SnapshotStore store = new LocalFileSnapshotStore(props);
        Reclaimer reclaimer = new Reclaimer(repository, backend, store, props, clock);
        LeaseService service = new LeaseService(repository, backend, reclaimer, store, props, clock);
        return new Harness(clock, service, repository, backend, reclaimer);
    }

    @Test
    void manualRetry_outcomesAreDistinguishable(@TempDir Path dir) {
        Harness h = harness(dir.resolve("s.json"));

        // NOT_FOUND：压根不存在
        ManualRetryResult notFound = h.reclaimer.manualRetry("ghost");
        System.out.printf("[input] 对不存在的环境手动重试%n[judge] outcome=%s detail=%s%n",
                notFound.outcome(), notFound.detail());
        assertEquals(ReclaimRetryOutcome.NOT_FOUND, notFound.outcome());

        // NOT_IN_RECLAIM：有效租期内，绝不能被提前回收
        h.service.acquire(new CreateRequest("active-env", "alice", 100_000));
        ManualRetryResult notInReclaim = h.reclaimer.manualRetry("active-env");
        System.out.printf("[input] 对有效租期内的环境手动重试%n[judge] outcome=%s state=%s 占用=%s%n",
                notInReclaim.outcome(), notInReclaim.state(), h.backend.isAllocated("active-env"));
        assertEquals(ReclaimRetryOutcome.NOT_IN_RECLAIM, notInReclaim.outcome());
        assertEquals(LeaseState.ACTIVE, h.repository.find("active-env").orElseThrow().getState());
        assertTrue(h.backend.isAllocated("active-env"), "有效租期内的环境不得被提前回收");
        assertEquals(0, h.backend.deallocateCalls("active-env"), "未触发任何归还动作");

        // RETRY_FAILED：到期且归还持续失败
        h.service.acquire(new CreateRequest("failing-env", "bob", 10_000));
        h.clock.advance(10_001); // failing-env 到期；active-env 仍有效
        h.backend.injectDeallocateFailureAlways("failing-env");
        ManualRetryResult failed = h.reclaimer.manualRetry("failing-env");
        System.out.printf("[input] 对到期且归还失败的环境手动重试%n[judge] outcome=%s state=%s attempts=%d detail=%s%n",
                failed.outcome(), failed.state(), failed.releaseAttempts(), failed.detail());
        assertEquals(ReclaimRetryOutcome.RETRY_FAILED, failed.outcome());
        assertEquals(LeaseState.RELEASING, failed.state());
        assertEquals(1, failed.releaseAttempts());
        assertEquals(1, h.backend.deallocateCalls("failing-env"));

        // RETRY_SUCCEEDED：故障恢复后重试成功
        h.backend.clearInjectedFailure("failing-env");
        ManualRetryResult succeeded = h.reclaimer.manualRetry("failing-env");
        System.out.printf("[input] 故障恢复后再次手动重试%n[judge] outcome=%s state=%s 占用=%s%n",
                succeeded.outcome(), succeeded.state(), h.backend.isAllocated("failing-env"));
        assertEquals(ReclaimRetryOutcome.RETRY_SUCCEEDED, succeeded.outcome());
        assertEquals(LeaseState.RELEASED, succeeded.state());

        // ALREADY_RELEASED：已归还干净，与“确实触发”区分
        int callsBefore = h.backend.deallocateCalls("failing-env");
        ManualRetryResult already = h.reclaimer.manualRetry("failing-env");
        System.out.printf("[input] 对已释放环境手动重试%n[judge] outcome=%s 归还调用数不变=%s%n",
                already.outcome(), h.backend.deallocateCalls("failing-env") == callsBefore);
        assertEquals(ReclaimRetryOutcome.ALREADY_RELEASED, already.outcome());
        assertEquals(callsBefore, h.backend.deallocateCalls("failing-env"),
                "ALREADY_RELEASED 不得产生任何归还副作用");
    }

    @Test
    void concurrentManualRetry_sameEnv_noDuplicateDeallocate(@TempDir Path dir) throws Exception {
        Harness h = harness(dir.resolve("s.json"));

        // target：释放时注入一次失败，停在 RELEASING 等待补救
        h.service.acquire(new CreateRequest("target", "alice", 10_000));
        h.backend.injectDeallocateFailureOnce("target");
        h.service.release("target", new ReleaseRequest("alice"));
        assertEquals(LeaseState.RELEASING, h.repository.find("target").orElseThrow().getState());
        int callsAfterRelease = h.backend.deallocateCalls("target");

        // 16 个线程同一时刻手动重试同一个环境
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ManualRetryResult>> futures = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> pool.submit(() -> {
                    gate.await();
                    return h.reclaimer.manualRetry("target");
                }))
                .collect(Collectors.toList());
        gate.countDown();
        List<ManualRetryResult> results = new java.util.ArrayList<>();
        for (Future<ManualRetryResult> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        Map<ReclaimRetryOutcome, Long> outcomeCounts = results.stream()
                .map(ManualRetryResult::outcome)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        int targetCalls = h.backend.deallocateCalls("target");
        System.out.printf("[input] target 停在 RELEASING(已失败1次); %d 线程并发手动重试同一环境%n", threads);
        System.out.printf("[judge] outcome 分布=%s, target 归还调用总数=%d(释放时%d+重试期%d), target=%s%n",
                outcomeCounts, targetCalls, callsAfterRelease, targetCalls - callsAfterRelease,
                h.repository.find("target").orElseThrow().getState());

        assertEquals(1L, outcomeCounts.getOrDefault(ReclaimRetryOutcome.RETRY_SUCCEEDED, 0L),
                "并发触发中只有真正执行归还的那次计为 RETRY_SUCCEEDED");
        assertEquals((long) threads - 1,
                outcomeCounts.getOrDefault(ReclaimRetryOutcome.ALREADY_RELEASED, 0L),
                "其余并发调用看到已归还干净，明确区分");
        assertEquals(callsAfterRelease + 1, targetCalls,
                "同一环境并发重试不得产生重复归还副作用");
        assertEquals(LeaseState.RELEASED, h.repository.find("target").orElseThrow().getState());
    }

    @Test
    void concurrentManualRetry_doesNotInterfereWithOtherEnvs(@TempDir Path dir) throws Exception {
        Harness h = harness(dir.resolve("s.json"));

        // target：停在 RELEASING；other-1/other-2：正常到期，由后台扫描回收
        h.service.acquire(new CreateRequest("target", "alice", 10_000));
        h.backend.injectDeallocateFailureOnce("target");
        h.service.release("target", new ReleaseRequest("alice"));
        h.service.acquire(new CreateRequest("other-1", "bob", 10_000));
        h.service.acquire(new CreateRequest("other-2", "carol", 10_000));
        h.clock.advance(10_001); // other-* 到期
        int callsBefore = h.backend.deallocateCalls("target");

        // 8 线程并发手动重试 target，同时另一线程跑后台扫描（会经过 target 与 other-*）
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ManualRetryResult>> futures = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> pool.submit(() -> {
                    gate.await();
                    return h.reclaimer.manualRetry("target");
                }))
                .collect(Collectors.toList());
        Future<Integer> scanFuture = pool.submit(() -> {
            gate.await();
            return h.reclaimer.reclaimExpired();
        });
        gate.countDown();
        for (Future<ManualRetryResult> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        int scanned = scanFuture.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        int targetCalls = h.backend.deallocateCalls("target");
        System.out.printf("[input] target 停在 RELEASING; %d 线程并发手动重试 target, "
                + "另一线程并发后台扫描(含 target 与 other-1/other-2)%n", threads);
        System.out.printf("[judge] target 重试期归还调用=%d, target=%s, other-1=%s, other-2=%s, "
                + "other-1 归还调用=%d, other-2 归还调用=%d, 扫描完成数=%d%n",
                targetCalls - callsBefore,
                h.repository.find("target").orElseThrow().getState(),
                h.repository.find("other-1").orElseThrow().getState(),
                h.repository.find("other-2").orElseThrow().getState(),
                h.backend.deallocateCalls("other-1"), h.backend.deallocateCalls("other-2"),
                scanned);

        assertEquals(callsBefore + 1, targetCalls,
                "手动重试与后台扫描竞争同一环境，归还副作用仍然只发生一次");
        assertEquals(LeaseState.RELEASED, h.repository.find("target").orElseThrow().getState());
        assertEquals(LeaseState.RELEASED, h.repository.find("other-1").orElseThrow().getState(),
                "并发手动重试不得干扰其他环境的回收");
        assertEquals(LeaseState.RELEASED, h.repository.find("other-2").orElseThrow().getState());
        assertEquals(1, h.backend.deallocateCalls("other-1"));
        assertEquals(1, h.backend.deallocateCalls("other-2"));
    }
}
