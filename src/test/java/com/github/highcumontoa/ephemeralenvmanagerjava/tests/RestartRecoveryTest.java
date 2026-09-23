package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.ReleaseRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
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
 * 服务重启恢复测试：
 * 第一次运行创建租约（一个存活、一个释放时归还失败而停在 RELEASING），
 * 第二次运行以同一快照文件启动恢复，验证租约状态、资源占用与待回收进度均可恢复且最终一致。
 */
class RestartRecoveryTest {

    @TempDir
    Path tempDir;

    private EphemeralProperties props(String snapshot) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(4);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(1_000_000);
        props.setSnapshotPath(snapshot);
        return props;
    }

    @Test
    void leases_and_pending_reclaim_survive_restart() throws Exception {
        String snapshot = tempDir.resolve("restart-snapshot.json").toString();

        // ===== 阶段一：第一次进程运行 =====
        EphemeralProperties props1 = props(snapshot);
        MutableClock clock1 = new MutableClock(1_000_000L);
        LeaseRepository repo1 = new InMemoryLeaseRepository();
        LocalResourceBackend backend1 = new LocalResourceBackend(props1);
        SnapshotStore store1 = new LocalFileSnapshotStore(props1);
        Reclaimer reclaimer1 = new Reclaimer(repo1, backend1, store1, clock1);
        LeaseService service1 = new LeaseService(repo1, backend1, reclaimer1, store1, props1, clock1);

        service1.acquire(new CreateRequest("surviving", "alice", 100_000));
        service1.acquire(new CreateRequest("pending", "bob", 10_000));
        backend1.injectDeallocateFailureOnce("pending");
        service1.release("pending", new ReleaseRequest("bob"));

        Lease pendingBefore = repo1.find("pending").orElseThrow();
        System.out.printf("[input] 进程1: surviving=ACTIVE 占用=%s; pending=%s attempts=%d 占用=%s%n",
                backend1.isAllocated("surviving"), pendingBefore.getState(),
                pendingBefore.getReleaseAttempts(), backend1.isAllocated("pending"));
        assertEquals(LeaseState.RELEASING, pendingBefore.getState());
        assertEquals(1, pendingBefore.getReleaseAttempts(), "失败尝试次数需持久化");
        assertTrue(backend1.isAllocated("pending"));

        // 再制造一个到期未回收的租约，验证重启后也能立即回收
        service1.acquire(new CreateRequest("expired", "carol", 10_000));
        clock1.advance(10_001);
        // 不主动扫描，模拟进程在到期后、回收前退出

        // ===== 阶段二：全新进程，空内存 + 同快照文件，执行启动恢复 =====
        // 使用与进程1相同的模拟时钟位置（仅推进到到期点之后），保证判定可解释。
        MutableClock clock2 = new MutableClock(1_000_000L + 10_001);
        EphemeralProperties props2 = props(snapshot);
        LeaseRepository repo2 = new InMemoryLeaseRepository();
        LocalResourceBackend backend2 = new LocalResourceBackend(props2);
        SnapshotStore store2 = new LocalFileSnapshotStore(props2);
        Reclaimer reclaimer2 = new Reclaimer(repo2, backend2, store2, clock2);
        RecoveryService recovery = new RecoveryService(store2, repo2, backend2, reclaimer2);
        recovery.run(null);

        Lease surviving = repo2.find("surviving").orElse(null);
        Lease pendingAfter = repo2.find("pending").orElse(null);
        Lease expiredAfter = repo2.find("expired").orElse(null);
        System.out.printf("[judge] 进程2恢复后: surviving=%s 占用=%s; pending=%s 占用=%s; expired=%s 占用=%s; 总占用=%d%n",
                surviving.getState(), backend2.isAllocated("surviving"),
                pendingAfter.getState(), backend2.isAllocated("pending"),
                expiredAfter.getState(), backend2.isAllocated("expired"),
                backend2.allocatedCount());

        assertNotNull(surviving, "有效租约必须从快照恢复");
        assertEquals(LeaseState.ACTIVE, surviving.getState());
        assertTrue(backend2.isAllocated("surviving"), "有效租约的资源占用需恢复");
        assertEquals(LeaseState.RELEASED, pendingAfter.getState(), "待回收进度恢复后应立即完成归还");
        assertFalse(backend2.isAllocated("pending"));
        assertEquals(LeaseState.RELEASED, expiredAfter.getState(), "到期未回收的租约重启后立即回收");
        assertFalse(backend2.isAllocated("expired"));
        assertEquals(1, backend2.allocatedCount(), "仅 surviving 占用资源，配额与实际一致");

        // 恢复后服务仍可正常工作：释放 surviving，资源归零
        var service2 = new LeaseService(repo2, backend2, reclaimer2, store2, props2, clock2);
        service2.release("surviving", new ReleaseRequest("alice"));
        System.out.printf("[judge] 恢复后释放 surviving: 状态=%s, 总占用=%d%n",
                repo2.find("surviving").orElseThrow().getState(), backend2.allocatedCount());
        assertEquals(LeaseState.RELEASED, repo2.find("surviving").orElseThrow().getState());
        assertEquals(0, backend2.allocatedCount());
    }
}
