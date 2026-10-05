package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.RejectReason;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.InMemoryLeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.LocalResourceBackend;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimProgress;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.LocalFileSnapshotStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡死兜底升级测试：长期回收失败的环境升级为 RECLAIM_STUCK 并从容量账目摘除，
 * 正常创建重新获得容量；有效租期内的环境绝不被兜底影响。
 */
class StuckEscalationTest {

    private static final long T0 = 5_000_000L;

    private record Harness(MutableClock clock, LeaseService service, LeaseRepository repository,
                           LocalResourceBackend backend, Reclaimer reclaimer) {
    }

    private Harness harness(Path snapshotFile, int stuckAfterAttempts, long stuckAfterMillis) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(2);
        props.setDefaultTtlMillis(10_000);
        props.setMaxTtlMillis(1_000_000);
        props.setSnapshotPath(snapshotFile.toString());
        props.setReclaimStuckAfterAttempts(stuckAfterAttempts);
        props.setReclaimStuckAfterMillis(stuckAfterMillis);
        MutableClock clock = new MutableClock(T0);
        LeaseRepository repository = new InMemoryLeaseRepository();
        LocalResourceBackend backend = new LocalResourceBackend(props);
        SnapshotStore store = new LocalFileSnapshotStore(props);
        Reclaimer reclaimer = new Reclaimer(repository, backend, store, props, clock);
        LeaseService service = new LeaseService(repository, backend, reclaimer, store, props, clock);
        return new Harness(clock, service, repository, backend, reclaimer);
    }

    @Test
    void longFailingReclaim_escalatesAndFreesCapacity_activeLeaseUntouched(@TempDir Path dir) {
        Harness h = harness(dir.resolve("s.json"), 3, 600_000);

        // 容量=2：stuck-env（短租期）+ active-env（长租期）占满
        h.service.acquire(new CreateRequest("stuck-env", "alice", 10_000));
        h.service.acquire(new CreateRequest("active-env", "bob", 500_000));
        h.clock.advance(10_001); // stuck-env 到期；active-env 仍在有效租期内
        h.backend.injectDeallocateFailureAlways("stuck-env");

        // 升级前：容量被卡住的环境占着，正常创建被拒
        LeaseException rejected = assertThrows(LeaseException.class,
                () -> h.service.acquire(new CreateRequest("new-env", "carol", 10_000)));
        System.out.printf("[input] 容量=2, stuck-env 到期但归还持续失败, active-env 有效; "
                + "阈值=失败3次%n[judge] 升级前创建 new-env 被拒 reason=%s, 占用数=%d%n",
                rejected.getReason(), h.backend.allocatedCount());
        assertEquals(RejectReason.QUOTA_EXCEEDED, rejected.getReason());
        assertEquals(2, h.backend.allocatedCount());

        // 连续 3 轮回收失败 -> 达到阈值升级
        for (int i = 1; i <= 3; i++) {
            h.reclaimer.reclaimExpired();
            System.out.printf("[judge] 第%d轮回收后 stuck-env=%s attempts=%d 占用数=%d%n", i,
                    h.repository.find("stuck-env").orElseThrow().getState(),
                    h.repository.find("stuck-env").orElseThrow().getReleaseAttempts(),
                    h.backend.allocatedCount());
        }
        var stuck = h.repository.find("stuck-env").orElseThrow();
        assertEquals(LeaseState.RECLAIM_STUCK, stuck.getState(), "失败3次后升级为可区分状态");
        assertEquals(3, stuck.getReleaseAttempts());
        assertTrue(stuck.getLastReclaimError().contains("persistent"));
        assertEquals(1, h.backend.allocatedCount(), "升级后从容量账目摘除，只剩 active-env");

        // 升级后：正常创建拿到本该空出的容量
        h.service.acquire(new CreateRequest("new-env", "carol", 10_000));
        System.out.printf("[judge] 升级后创建 new-env=%s, 占用数=%d%n",
                h.repository.find("new-env").orElseThrow().getState(), h.backend.allocatedCount());
        assertEquals(LeaseState.ACTIVE, h.repository.find("new-env").orElseThrow().getState());

        // 有效租期内的环境绝不被兜底影响
        var active = h.repository.find("active-env").orElseThrow();
        System.out.printf("[judge] active-env=%s 占用=%s (有效租期内不受兜底影响)%n",
                active.getState(), h.backend.isAllocated("active-env"));
        assertEquals(LeaseState.ACTIVE, active.getState());
        assertTrue(h.backend.isAllocated("active-env"));

        // 升级状态可追溯：进度查询可见阶段/次数/最近失败原因
        List<ReclaimProgress> pending = h.reclaimer.listPendingReclaims();
        System.out.printf("[judge] 进度查询=%s%n", pending.stream()
                .map(p -> p.name() + ":" + p.stage() + ":attempts=" + p.releaseAttempts()
                        + ":err=" + p.lastReclaimError())
                .toList());
        assertEquals(1, pending.size());
        assertEquals(LeaseState.RECLAIM_STUCK, pending.get(0).stage());

        // 故障恢复后：卡住的环境仍可被重试归还，终态一致
        h.backend.clearInjectedFailure("stuck-env");
        h.reclaimer.reclaimExpired();
        System.out.printf("[judge] 故障恢复后 stuck-env=%s, 进度查询剩余=%d%n",
                h.repository.find("stuck-env").orElseThrow().getState(),
                h.reclaimer.listPendingReclaims().size());
        assertEquals(LeaseState.RELEASED, h.repository.find("stuck-env").orElseThrow().getState());
        assertFalse(h.backend.isAllocated("stuck-env"));
        assertTrue(h.reclaimer.listPendingReclaims().isEmpty());
    }

    @Test
    void stuckByDuration_alsoEscalates(@TempDir Path dir) {
        // 失败次数阈值调得很高，只靠停留时长触发升级
        Harness h = harness(dir.resolve("s.json"), 100, 5_000);

        h.service.acquire(new CreateRequest("slow-stuck", "alice", 10_000));
        h.clock.advance(10_001);
        h.backend.injectDeallocateFailureAlways("slow-stuck");

        h.reclaimer.reclaimExpired(); // 第 1 次失败，进入 RELEASING（此刻计时开始）
        var mid = h.repository.find("slow-stuck").orElseThrow();
        System.out.printf("[input] 次数阈值=100, 时长阈值=5000ms; slow-stuck 第1次失败%n"
                + "[judge] 第1次失败后 state=%s attempts=%d%n", mid.getState(), mid.getReleaseAttempts());
        assertEquals(LeaseState.RELEASING, mid.getState());

        h.clock.advance(5_001); // 停留时长超过阈值
        h.reclaimer.reclaimExpired(); // 再次失败 -> 按时长升级
        var after = h.repository.find("slow-stuck").orElseThrow();
        System.out.printf("[judge] 停留5001ms后再失败: state=%s attempts=%d 占用数=%d%n",
                after.getState(), after.getReleaseAttempts(), h.backend.allocatedCount());
        assertEquals(LeaseState.RECLAIM_STUCK, after.getState(),
                "停留时长超阈值同样升级（次数远未到阈值）");
        assertEquals(2, after.getReleaseAttempts());
        assertEquals(0, h.backend.allocatedCount(), "升级后容量被释放");
    }
}
