package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.LocalFileSnapshotStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收失败重试、幂等性与单环境失败隔离测试。
 */
class ReclaimRetryTest {

    private static final long T0 = 5_000_000L;

    private record Harness(MutableClock clock, LeaseService service, LeaseRepository repository,
                           LocalResourceBackend backend, Reclaimer reclaimer, SnapshotStore store) {
    }

    private Harness harness(Path snapshotFile) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(8);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(1_000_000);
        props.setSnapshotPath(snapshotFile.toString());
        MutableClock clock = new MutableClock(T0);
        LeaseRepository repository = new InMemoryLeaseRepository();
        LocalResourceBackend backend = new LocalResourceBackend(props);
        SnapshotStore store = new LocalFileSnapshotStore(props);
        Reclaimer reclaimer = new Reclaimer(repository, backend, store, props, clock);
        LeaseService service = new LeaseService(repository, backend, reclaimer, store, props, clock);
        return new Harness(clock, service, repository, backend, reclaimer, store);
    }

    @Test
    void deallocateFailure_retried_thenSucceeds_andIdempotent(@TempDir Path dir) {
        Harness h = harness(dir.resolve("snapshot.json"));
        h.service.acquire(new CreateRequest("env-x", "alice", 10_000));
        h.clock.advance(10_001); // 到期

        h.backend.injectDeallocateFailureOnce("env-x");
        int firstPass = h.reclaimer.reclaimExpired();

        Lease afterFailure = h.repository.find("env-x").orElseThrow();
        System.out.printf("[input] env-x 到期，注入一次归还失败%n");
        System.out.printf("[judge] 首次回收返回=%d, 状态=%s, 尝试次数=%d, 资源仍占用=%s%n",
                firstPass, afterFailure.getState(), afterFailure.getReleaseAttempts(),
                h.backend.isAllocated("env-x"));
        assertEquals(0, firstPass, "失败不计为完成");
        assertEquals(LeaseState.RELEASING, afterFailure.getState());
        assertEquals(1, afterFailure.getReleaseAttempts());
        assertTrue(h.backend.isAllocated("env-x"), "归还失败时资源不得丢失（不能提前释放）");

        int secondPass = h.reclaimer.reclaimExpired();
        Lease afterRetry = h.repository.find("env-x").orElseThrow();
        System.out.printf("[judge] 重试回收返回=%d, 状态=%s, 资源仍占用=%s%n",
                secondPass, afterRetry.getState(), h.backend.isAllocated("env-x"));
        assertEquals(1, secondPass);
        assertEquals(LeaseState.RELEASED, afterRetry.getState());
        assertFalse(h.backend.isAllocated("env-x"), "重试成功后资源必须归还");

        // 重复回收不得产生副作用：已终结租约不再被扫描；直接重复归还也幂等。
        int thirdPass = h.reclaimer.reclaimExpired();
        boolean directRepeat = h.reclaimer.reclaimOne("env-x");
        System.out.printf("[judge] 再次扫描返回=%d, 直接重复回收=%b, 状态保持=%s, 占用=%s%n",
                thirdPass, directRepeat, h.repository.find("env-x").orElseThrow().getState(),
                h.backend.isAllocated("env-x"));
        assertEquals(0, thirdPass, "已终结租约不再进入扫描");
        assertTrue(directRepeat, "直接重复回收幂等成功");
        assertEquals(LeaseState.RELEASED, h.repository.find("env-x").orElseThrow().getState());
        assertFalse(h.backend.isAllocated("env-x"), "重复回收不产生副作用");
    }

    @Test
    void singleFailureDoesNotBlockOthers(@TempDir Path dir) {
        Harness h = harness(dir.resolve("snapshot.json"));
        h.service.acquire(new CreateRequest("bad", "o", 10_000));
        h.service.acquire(new CreateRequest("good", "o", 10_000));
        h.clock.advance(10_001);

        h.backend.injectDeallocateFailureOnce("bad");
        int reclaimed = h.reclaimer.reclaimExpired();

        System.out.printf("[input] bad/good 同时到期，bad 注入归还失败%n");
        System.out.printf("[judge] 本轮完成回收数=%d, good=%s, bad=%s, 底层占用数=%d%n",
                reclaimed,
                h.repository.find("good").orElseThrow().getState(),
                h.repository.find("bad").orElseThrow().getState(),
                h.backend.allocatedCount());
        assertEquals(1, reclaimed, "bad 失败不得阻塞 good 回收");
        assertEquals(LeaseState.RELEASED, h.repository.find("good").orElseThrow().getState());
        assertEquals(LeaseState.RELEASING, h.repository.find("bad").orElseThrow().getState());
        assertEquals(1, h.backend.allocatedCount(), "只有 bad 的资源残留");

        // bad 重试成功后，容量应完全归还，且可立即创建新环境
        int second = h.reclaimer.reclaimExpired();
        h.service.acquire(new CreateRequest("new-env", "o", 10_000));
        System.out.printf("[judge] bad 重试完成数=%d, 重试后状态=%s, 新环境创建成功, 占用数=%d%n",
                second, h.repository.find("bad").orElseThrow().getState(),
                h.backend.allocatedCount());
        assertEquals(1, second);
        assertEquals(LeaseState.RELEASED, h.repository.find("bad").orElseThrow().getState());
        assertEquals(LeaseState.ACTIVE, h.repository.find("new-env").orElseThrow().getState());
    }
}
