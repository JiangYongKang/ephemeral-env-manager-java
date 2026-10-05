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
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimPhase;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimQueryService;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimRetryOutcome;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.LocalFileSnapshotStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.recovery.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 卡死兜底测试：长期回收失败的环境被升级为 QUARANTINED 并摘除容量占用，
 * 正常创建重新获得容量；有效租期内的环境绝不被兜底影响。
 */
class QuarantineCapacityTest {

    private static final long T0 = 9_000_000L;
    private static final long THRESHOLD = 5_000L;

    private record Harness(MutableClock clock, LeaseService service, LeaseRepository repository,
                           LocalResourceBackend backend, Reclaimer reclaimer,
                           ReclaimQueryService query) {
    }

    private Harness harness(Path snapshotFile) {
        EphemeralProperties props = new EphemeralProperties();
        props.setMaxActiveEnvironments(3);
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
    void stuckReclaim_isQuarantined_andCapacityIsFreedForNormalCreates(@TempDir Path dir) {
        Harness h = harness(dir.resolve("snapshot.json"));
        // env-live：长租期有效环境；env-stuck：短租期，归还将持续失败。
        h.service.acquire(new CreateRequest("env-live", "alice", 1_000_000));
        h.service.acquire(new CreateRequest("env-stuck", "bob", 10_000));
        h.clock.advance(10_001); // env-stuck 到期
        h.backend.injectDeallocateFailureAlways("env-stuck");
        h.reclaimer.reclaimExpired(); // env-stuck -> RELEASING attempts=1

        // 容量被卡死环境占住：再建两个即满，第三个被拒绝。
        h.service.acquire(new CreateRequest("env-c", "carol", 10_000_000));
        try {
            h.service.acquire(new CreateRequest("env-d", "dave", 10_000_000));
            fail("容量被卡死环境占满时应拒绝");
        } catch (LeaseException e) {
            System.out.printf("[judge] 升级前创建 env-d 被拒: reason=%s (占用=%d/3)%n",
                    e.getReason(), h.backend.allocatedCount());
            assertEquals(RejectReason.QUOTA_EXCEEDED, e.getReason());
        }

        // 推进超过卡死阈值（env-live 仍在有效租期内），扫描触发兜底升级。
        h.clock.advance(THRESHOLD + 1);
        h.reclaimer.reclaimExpired();

        var stuck = h.repository.find("env-stuck").orElseThrow();
        var live = h.repository.find("env-live").orElseThrow();
        System.out.printf("[input] env-stuck 在 RELEASING 停留 %dms > 阈值 %dms；env-live 仍在有效租期%n",
                THRESHOLD + 2, THRESHOLD);
        System.out.printf("[judge] env-stuck=%s attempts=%d reason=%s 占用=%s; env-live=%s 占用=%s; 总占用=%d/3%n",
                stuck.getState(), stuck.getReleaseAttempts(), stuck.getLastFailureReason(),
                h.backend.isAllocated("env-stuck"), live.getState(),
                h.backend.isAllocated("env-live"), h.backend.allocatedCount());

        assertEquals(LeaseState.QUARANTINED, stuck.getState(), "卡死环境应升级为 QUARANTINED");
        assertEquals(2, stuck.getReleaseAttempts(), "升级当轮的重试次数被保留可追溯");
        assertNotNull(stuck.getLastFailureReason(), "最近失败原因被保留可追溯");
        assertFalse(h.backend.isAllocated("env-stuck"), "兜底后从容量账目摘除");
        assertEquals(LeaseState.ACTIVE, live.getState(), "有效租期内的环境绝不被兜底影响");
        assertTrue(h.backend.isAllocated("env-live"));
        assertEquals(2, h.backend.allocatedCount(), "只剩 env-live 与 env-c 占容量");

        // 之前被拒绝的创建现在应成功：容量被正常释放出来了。
        h.service.acquire(new CreateRequest("env-d", "dave", 10_000_000));
        System.out.printf("[judge] 升级后创建 env-d 成功: 状态=%s, 总占用=%d/3%n",
                h.repository.find("env-d").orElseThrow().getState(), h.backend.allocatedCount());
        assertEquals(LeaseState.ACTIVE, h.repository.find("env-d").orElseThrow().getState());
        assertEquals(3, h.backend.allocatedCount());

        // 进度查询中可识别为 QUARANTINED 阶段。
        var views = h.query().listUnfinished();
        assertEquals(1, views.size());
        assertEquals("env-stuck", views.get(0).name());
        assertEquals(ReclaimPhase.QUARANTINED, views.get(0).phase());
        System.out.printf("[judge] 进度查询: %s phase=%s attempts=%d stuck=%dms%n",
                views.get(0).name(), views.get(0).phase(),
                views.get(0).releaseAttempts(), views.get(0).stuckMillis());
    }

    @Test
    void quarantinedEnv_notRetriedByBackground_butManualRetryRemediates(@TempDir Path dir) {
        Harness h = harness(dir.resolve("snapshot.json"));
        h.service.acquire(new CreateRequest("env-stuck", "bob", 10_000));
        h.clock.advance(10_001);
        h.backend.injectDeallocateFailureAlways("env-stuck");
        h.reclaimer.reclaimExpired(); // RELEASING attempts=1
        h.clock.advance(THRESHOLD + 1);
        h.reclaimer.reclaimExpired(); // -> QUARANTINED
        assertEquals(LeaseState.QUARANTINED, h.repository.find("env-stuck").orElseThrow().getState());

        // 故障恢复后，后台扫描不再自动重试 QUARANTINED 环境。
        h.backend.clearDeallocateFailure("env-stuck");
        int callsBefore = h.backend.deallocateCallCount("env-stuck");
        h.reclaimer.reclaimExpired();
        h.reclaimer.reclaimExpired();
        int backgroundCalls = h.backend.deallocateCallCount("env-stuck") - callsBefore;
        System.out.printf("[input] env-stuck 已 QUARANTINED 且故障恢复，跑两轮后台扫描%n");
        System.out.printf("[judge] 后台归还调用=%d, 状态=%s%n",
                backgroundCalls, h.repository.find("env-stuck").orElseThrow().getState());
        assertEquals(0, backgroundCalls, "QUARANTINED 不被后台扫描重试");
        assertEquals(LeaseState.QUARANTINED, h.repository.find("env-stuck").orElseThrow().getState());

        // 手动重试定点补救：迁回 RELEASING 重试，成功即 RELEASED。
        var result = h.reclaimer.retryOne("env-stuck");
        System.out.printf("[judge] 手动重试: outcome=%s, 状态=%s, 占用=%s%n",
                result.outcome(), result.state(), h.backend.isAllocated("env-stuck"));
        assertEquals(ReclaimRetryOutcome.TRIGGERED, result.outcome());
        assertEquals(LeaseState.RELEASED, result.state());
        assertEquals(LeaseState.RELEASED, h.repository.find("env-stuck").orElseThrow().getState());
        assertEquals(0, h.backend.allocatedCount(), "补救成功后容量完全干净");
        assertEquals(0, h.query().listUnfinished().size(), "不再出现在未回收干净列表");
    }
}
