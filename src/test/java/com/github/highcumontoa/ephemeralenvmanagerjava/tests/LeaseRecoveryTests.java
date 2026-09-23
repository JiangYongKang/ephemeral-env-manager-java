package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseStatus;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.RejectReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务重启后租约状态与待回收进度恢复：用同一状态文件构造全新服务实例模拟进程重启。
 */
class LeaseRecoveryTests {

    @TempDir
    Path tmp;

    @Test
    void activeLeasesSurviveRestartWithDeadlineAndVersion() {
        var s = new LeaseTestSupport(tmp, 3, 60_000);
        var env1 = s.service.create("env-1", "team-1", 10_000L).lease();
        s.service.create("env-2", "team-2", 20_000L);
        s.clock.advance(1_000);
        s.service.renew("env-1", "team-1", 5_000L);

        // 模拟重启：丢弃内存服务，用同一 JSON 文件构造新实例
        var restarted = s.restartedService(3, 60_000);
        Lease r1 = restarted.get("env-1");
        Lease r2 = restarted.get("env-2");
        assertEquals(LeaseStatus.ACTIVE, r1.getStatus());
        assertEquals(env1.getResourceId(), r1.getResourceId());
        assertEquals(env1.getDeadlineMillis() + 5_000, r1.getDeadlineMillis());
        assertEquals(2, r1.getVersion());
        assertEquals("team-2", r2.getOwner());
        System.out.printf("[输入] 重启恢复：env-1 status=%s deadline=%d version=%d owner/resource 一致%n",
                r1.getStatus(), r1.getDeadlineMillis(), r1.getVersion());

        // 恢复后仍在有效期：不会被回收；时钟走到到期后可被新实例正常回收
        assertFalse(restarted.reclaim("env-1").released());
        s.clock.advance(20_000);
        var outcomes = restarted.reclaimExpired();
        assertEquals(2, outcomes.size());
        assertTrue(outcomes.stream().allMatch(o -> o.released()));
        System.out.println("[输入] 恢复后的实例在到期后完成全部回收，过期环境未长期残留");
    }

    @Test
    void reclaimFailedProgressSurvivesRestartAndCanBeRetried() {
        var s = new LeaseTestSupport(tmp, 2, 10_000);
        var bad = s.service.create("bad", "team-1", 10_000L).lease();
        s.reclaimer.failNextAttempts(bad.getResourceId(), 1);
        s.clock.advance(10_001);
        var o1 = s.service.reclaim("bad");
        assertFalse(o1.released());
        assertEquals(LeaseStatus.RECLAIM_FAILED, s.service.get("bad").getStatus());
        System.out.printf("[输入] 重启前：bad 回收失败 status=%s attempts=%d err=%s%n",
                s.service.get("bad").getStatus(), s.service.get("bad").getReclaimAttempts(),
                s.service.get("bad").getLastErrorMessage());

        // 重启：失败状态、尝试次数、失败原因都必须可解释地恢复
        var restarted = s.restartedService(2, 10_000);
        Lease recovered = restarted.get("bad");
        assertEquals(LeaseStatus.RECLAIM_FAILED, recovered.getStatus());
        assertEquals(1, recovered.getReclaimAttempts());
        assertNotNull(recovered.getLastErrorMessage());
        System.out.printf("[断言] 重启恢复待回收进度：status=%s attempts=%d lastError=%s%n",
                recovered.getStatus(), recovered.getReclaimAttempts(), recovered.getLastErrorMessage());

        // 注入的失败次数已在重启前耗尽，重启后重试即成功，资源只归还一次
        var o2 = restarted.reclaim("bad");
        assertTrue(o2.released());
        assertTrue(s.reclaimer.isReleased(bad.getResourceId()));
        var o3 = restarted.reclaim("bad");
        assertFalse(o3.released());
        System.out.printf("[输入] 重启后重试回收：%s；再次回收为空操作：%s%n", o2.detail(), o3.detail());
    }

    @Test
    void releasedHistorySurvivesRestartAndRemainsDistinguishable() {
        var s = new LeaseTestSupport(tmp, 2, 10_000);
        s.service.create("gone", "team-1", 10_000L);
        s.service.release("gone", "team-1");

        var restarted = s.restartedService(2, 10_000);
        LeaseException ex = assertThrows(LeaseException.class, () -> restarted.get("gone"));
        assertEquals(RejectReason.ALREADY_RELEASED, ex.getReason(),
                "重启后已释放与不存在仍须可区分");
        var rec = s.store.findReleased("gone").orElseThrow();
        assertEquals("MANUAL_RELEASE", rec.getReason());
        System.out.printf("[断言] 重启后 gone 判定=%s（区别于 NOT_FOUND），释放原因=%s%n",
                ex.getReason(), rec.getReason());

        // 从未创建过的名称仍是 NOT_FOUND
        assertEquals(RejectReason.NOT_FOUND,
                assertThrows(LeaseException.class, () -> restarted.get("never")).getReason());
    }

    @Test
    void reclaimingStateLeftByCrashIsResumedAfterRestart() {
        // 模拟崩溃残留：直接构造一个停在 RECLAIMING 的租约并落盘，
        // 重启后的扫描必须能继续完成回收（RECLAIMING 是可重入的恢复入口）。
        var s = new LeaseTestSupport(tmp, 2, 10_000);
        var lease = s.service.create("stuck", "team-1", 10_000L).lease();
        s.store.mutate("stuck", existing -> {
            Lease l = existing.orElseThrow();
            l.setStatus(LeaseStatus.RECLAIMING);
            l.setReclaimAttempts(2);
            l.setLastErrorMessage("模拟上次进程在回收中途崩溃");
            return l;
        });

        var restarted = s.restartedService(2, 10_000);
        var outcome = restarted.reclaim("stuck");
        assertTrue(outcome.released());
        assertTrue(s.reclaimer.isReleased(lease.getResourceId()));
        System.out.printf("[输入] 崩溃残留 RECLAIMING 租约在重启后继续回收：%s%n", outcome.detail());
    }
}
