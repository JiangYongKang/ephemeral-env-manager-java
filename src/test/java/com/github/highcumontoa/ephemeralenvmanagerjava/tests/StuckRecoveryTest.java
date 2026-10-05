package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimProgress;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.LocalFileSnapshotStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.RecoveryService;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重启恢复测试（卡死兜底场景）：RECLAIM_STUCK 状态、重试进度（次数/最近失败原因/
 * 阶段进入时间）与“已摘除容量”的账目关系，重启后全部照旧恢复。
 */
class StuckRecoveryTest {

    private static final long T0 = 5_000_000L;

    @TempDir
    Path tempDir;

    private EphemeralProperties props(String snapshot) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(2);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(1_000_000);
        props.setSnapshotPath(snapshot);
        props.setReclaimStuckAfterAttempts(2);
        props.setReclaimStuckAfterMillis(600_000);
        return props;
    }

    @Test
    void stuckState_progressAndCapacityAccounting_surviveRestart() throws Exception {
        String snapshot = tempDir.resolve("stuck-restart-snapshot.json").toString();

        // ===== 进程 1：制造一个 RECLAIM_STUCK 环境 =====
        EphemeralProperties props1 = props(snapshot);
        MutableClock clock1 = new MutableClock(T0);
        LeaseRepository repo1 = new InMemoryLeaseRepository();
        LocalResourceBackend backend1 = new LocalResourceBackend(props1);
        SnapshotStore store1 = new LocalFileSnapshotStore(props1);
        Reclaimer reclaimer1 = new Reclaimer(repo1, backend1, store1, props1, clock1);
        LeaseService service1 = new LeaseService(repo1, backend1, reclaimer1, store1, props1, clock1);

        service1.acquire(new CreateRequest("stuck-env", "alice", 10_000));
        service1.acquire(new CreateRequest("survivor", "bob", 500_000));
        clock1.advance(10_001); // stuck-env 到期
        backend1.injectDeallocateFailureAlways("stuck-env");
        reclaimer1.reclaimExpired(); // 失败 1 次
        reclaimer1.reclaimExpired(); // 失败 2 次 -> 达到阈值升级

        Lease before = repo1.find("stuck-env").orElseThrow();
        System.out.printf("[input] 进程1: stuck-env=%s attempts=%d err=%s 占用数=%d(容量2), survivor=ACTIVE%n",
                before.getState(), before.getReleaseAttempts(),
                before.getLastReclaimError(), backend1.allocatedCount());
        assertEquals(LeaseState.RECLAIM_STUCK, before.getState());
        assertEquals(2, before.getReleaseAttempts());
        assertEquals(1, backend1.allocatedCount(), "升级后 stuck-env 已摘除，仅 survivor 占容量");
        long stuckEnteredAt = before.getStateEnteredAtMillis();

        // ===== 进程 2：全新内存 + 同快照文件恢复；底层故障仍在（重新注入） =====
        EphemeralProperties props2 = props(snapshot);
        MutableClock clock2 = new MutableClock(T0 + 10_001);
        LeaseRepository repo2 = new InMemoryLeaseRepository();
        LocalResourceBackend backend2 = new LocalResourceBackend(props2);
        backend2.injectDeallocateFailureAlways("stuck-env"); // 模拟重启后归还仍然失败
        SnapshotStore store2 = new LocalFileSnapshotStore(props2);
        Reclaimer reclaimer2 = new Reclaimer(repo2, backend2, store2, props2, clock2);
        RecoveryService recovery = new RecoveryService(store2, repo2, backend2, reclaimer2);
        recovery.run(null);

        Lease restored = repo2.find("stuck-env").orElseThrow();
        System.out.printf("[judge] 进程2恢复: stuck-env=%s attempts=%d err=%s enteredAt=%d, "
                + "占用数=%d, stuck-env占容量=%s%n",
                restored.getState(), restored.getReleaseAttempts(),
                restored.getLastReclaimError(), restored.getStateEnteredAtMillis(),
                backend2.allocatedCount(), backend2.isAllocated("stuck-env"));
        assertEquals(LeaseState.RECLAIM_STUCK, restored.getState(), "升级状态需恢复");
        assertEquals(3, restored.getReleaseAttempts(),
                "重试进度需从持久化的 2 次继续累计（恢复时的立即回收又失败 1 次 -> 3；若被重置则会是 1）");
        assertTrue(restored.getLastReclaimError().contains("persistent"), "最近失败原因需恢复");
        assertEquals(stuckEnteredAt, restored.getStateEnteredAtMillis(), "阶段进入时间需恢复");
        assertFalse(backend2.isAllocated("stuck-env"), "已摘除容量的环境重启后不得回补占用");
        assertEquals(1, backend2.allocatedCount(), "仅 survivor 恢复占用，配额账目一致");

        // 恢复后进度查询立即可用
        List<ReclaimProgress> pending = reclaimer2.listPendingReclaims();
        System.out.printf("[judge] 恢复后进度查询=%s%n", pending.stream()
                .map(p -> p.name() + ":" + p.stage() + ":attempts=" + p.releaseAttempts())
                .toList());
        assertEquals(1, pending.size());
        assertEquals(LeaseState.RECLAIM_STUCK, pending.get(0).stage());
        assertEquals(3, pending.get(0).releaseAttempts());

        // 恢复后容量立即可供正常创建（不被卡住的环境冤枉拒绝）
        LeaseService service2 = new LeaseService(repo2, backend2, reclaimer2, store2, props2, clock2);
        service2.acquire(new CreateRequest("new-env", "carol", 10_000));
        System.out.printf("[judge] 恢复后创建 new-env=%s, 占用数=%d%n",
                repo2.find("new-env").orElseThrow().getState(), backend2.allocatedCount());
        assertEquals(LeaseState.ACTIVE, repo2.find("new-env").orElseThrow().getState());

        // 故障恢复后：卡住的环境继续重试直至归还干净
        backend2.clearInjectedFailure("stuck-env");
        reclaimer2.reclaimExpired();
        System.out.printf("[judge] 故障恢复后 stuck-env=%s, 进度查询剩余=%d%n",
                repo2.find("stuck-env").orElseThrow().getState(),
                reclaimer2.listPendingReclaims().size());
        assertEquals(LeaseState.RELEASED, repo2.find("stuck-env").orElseThrow().getState());
        assertTrue(reclaimer2.listPendingReclaims().isEmpty());
    }
}
