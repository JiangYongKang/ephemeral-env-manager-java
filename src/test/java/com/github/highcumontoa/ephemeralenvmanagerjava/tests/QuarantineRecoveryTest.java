package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimPhase;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimQueryService;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimRetryOutcome;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.LocalFileSnapshotStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.RecoveryService;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重启恢复测试（卡死兜底场景）：
 * 进程 1 把长期回收失败的环境升级为 QUARANTINED 并摘除容量；
 * 进程 2 以空内存 + 同快照恢复，验证隔离状态、重试进度、容量视图均可恢复，
 * 且恢复后仍可手动补救。
 */
class QuarantineRecoveryTest {

    private static final long T0 = 11_000_000L;
    private static final long THRESHOLD = 5_000L;

    @TempDir
    Path tempDir;

    private EphemeralProperties props(String snapshot) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(2);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(10_000_000);
        props.setReclaimStuckThresholdMillis(THRESHOLD);
        props.setSnapshotPath(snapshot);
        return props;
    }

    @Test
    void quarantinedLease_andItsProgress_surviveRestart() {
        String snapshot = tempDir.resolve("quarantine-snapshot.json").toString();

        // ===== 进程 1：制造一个被兜底的卡死环境 =====
        EphemeralProperties props1 = props(snapshot);
        MutableClock clock1 = new MutableClock(T0);
        LeaseRepository repo1 = new InMemoryLeaseRepository();
        LocalResourceBackend backend1 = new LocalResourceBackend(props1);
        SnapshotStore store1 = new LocalFileSnapshotStore(props1);
        Reclaimer reclaimer1 = new Reclaimer(repo1, backend1, store1, props1, clock1);
        LeaseService service1 = new LeaseService(repo1, backend1, reclaimer1, store1, props1, clock1);

        service1.acquire(new CreateRequest("env-live", "alice", 1_000_000));
        service1.acquire(new CreateRequest("env-stuck", "bob", 10_000));
        clock1.advance(10_001);
        backend1.injectDeallocateFailureAlways("env-stuck");
        reclaimer1.reclaimExpired(); // RELEASING attempts=1
        reclaimer1.reclaimExpired(); // RELEASING attempts=2
        clock1.advance(THRESHOLD + 1);
        reclaimer1.reclaimExpired(); // 再失败一次 attempts=3 -> QUARANTINED + 摘除容量

        Lease stuckBefore = repo1.find("env-stuck").orElseThrow();
        long enteredAtBefore = stuckBefore.getStateEnteredAtMillis();
        System.out.printf("[input] 进程1: env-stuck=%s attempts=%d reason=%s 占用=%s; env-live=%s; 总占用=%d/2%n",
                stuckBefore.getState(), stuckBefore.getReleaseAttempts(),
                stuckBefore.getLastFailureReason(), backend1.isAllocated("env-stuck"),
                repo1.find("env-live").orElseThrow().getState(), backend1.allocatedCount());
        assertEquals(LeaseState.QUARANTINED, stuckBefore.getState());
        assertEquals(3, stuckBefore.getReleaseAttempts());
        assertEquals(1, backend1.allocatedCount(), "env-stuck 已摘除，仅 env-live 占容量");

        // ===== 进程 2：空内存 + 同快照恢复 =====
        MutableClock clock2 = new MutableClock(T0 + 10_001 + THRESHOLD + 1);
        EphemeralProperties props2 = props(snapshot);
        LeaseRepository repo2 = new InMemoryLeaseRepository();
        LocalResourceBackend backend2 = new LocalResourceBackend(props2);
        SnapshotStore store2 = new LocalFileSnapshotStore(props2);
        Reclaimer reclaimer2 = new Reclaimer(repo2, backend2, store2, props2, clock2);
        new RecoveryService(store2, repo2, backend2, reclaimer2).run(null);

        Lease stuckAfter = repo2.find("env-stuck").orElseThrow();
        Lease liveAfter = repo2.find("env-live").orElseThrow();
        System.out.printf("[judge] 进程2恢复: env-stuck=%s attempts=%d reason=%s enteredAt一致=%s 占用=%s;"
                        + " env-live=%s 占用=%s; 总占用=%d/2%n",
                stuckAfter.getState(), stuckAfter.getReleaseAttempts(),
                stuckAfter.getLastFailureReason(),
                stuckAfter.getStateEnteredAtMillis() == enteredAtBefore,
                backend2.isAllocated("env-stuck"),
                liveAfter.getState(), backend2.isAllocated("env-live"), backend2.allocatedCount());

        assertEquals(LeaseState.QUARANTINED, stuckAfter.getState(), "隔离状态必须恢复");
        assertEquals(3, stuckAfter.getReleaseAttempts(), "重试进度必须恢复");
        assertNotNull(stuckAfter.getLastFailureReason(), "最近失败原因必须恢复");
        assertEquals(enteredAtBefore, stuckAfter.getStateEnteredAtMillis(), "阶段进入时刻必须恢复");
        assertFalse(backend2.isAllocated("env-stuck"), "隔离环境恢复后仍不占容量");
        assertEquals(LeaseState.ACTIVE, liveAfter.getState());
        assertTrue(backend2.isAllocated("env-live"));
        assertEquals(1, backend2.allocatedCount(), "容量视图与重启前一致");

        // 恢复后进度查询立即可见该隔离环境。
        var query = new ReclaimQueryService(repo2, clock2);
        var views = query.listUnfinished();
        assertEquals(1, views.size());
        assertEquals(ReclaimPhase.QUARANTINED, views.get(0).phase());
        System.out.printf("[judge] 恢复后进度查询: %s phase=%s attempts=%d stuck=%dms%n",
                views.get(0).name(), views.get(0).phase(),
                views.get(0).releaseAttempts(), views.get(0).stuckMillis());

        // 恢复后容量可用于正常创建（进程2容量上限同为 2）。
        LeaseService service2 = new LeaseService(repo2, backend2, reclaimer2, store2, props2, clock2);
        service2.acquire(new CreateRequest("env-new", "carol", 10_000));
        assertEquals(LeaseState.ACTIVE, repo2.find("env-new").orElseThrow().getState());
        System.out.printf("[judge] 恢复后新环境 env-new 创建成功, 总占用=%d/2%n", backend2.allocatedCount());

        // 恢复后故障消除，手动重试可把隔离环境补救到终结态。
        backend2.clearDeallocateFailure("env-stuck");
        var retry = reclaimer2.retryOne("env-stuck");
        System.out.printf("[judge] 恢复后手动重试 env-stuck: outcome=%s state=%s 查询残留=%d%n",
                retry.outcome(), retry.state(), query.listUnfinished().size());
        assertEquals(ReclaimRetryOutcome.TRIGGERED, retry.outcome());
        assertEquals(LeaseState.RELEASED, retry.state());
        assertEquals(0, query.listUnfinished().size());
    }
}
