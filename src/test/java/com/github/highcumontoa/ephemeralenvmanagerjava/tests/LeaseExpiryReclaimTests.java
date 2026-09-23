package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseStatus;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.ReclaimOutcome;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.RejectReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 到期回收、续租与到期竞争、回收失败重试、单环境失败隔离、时钟异常。
 */
class LeaseExpiryReclaimTests {

    @TempDir
    Path tmp;

    @Test
    void expiredLeaseReclaimedIdempotentlyAndActiveLeaseUntouched() {
        var s = new LeaseTestSupport(tmp, 3, 10_000);
        var soon = s.service.create("soon", "team-1", 10_000L).lease();
        var later = s.service.create("later", "team-1", 20_000L).lease();

        // 越过 soon 的 deadline：soon 到期（now=deadline+1），later 仍有效（deadline=1020000）
        s.clock.advance(10_001);
        assertEquals(LeaseStatus.ACTIVE, s.service.get("soon").getStatus()); // 惰性判定，get 不改状态

        // 对仍在有效期内的环境强制回收必须被拒绝，绝不误回收
        ReclaimOutcome protectedOutcome = s.service.reclaim("later");
        assertFalse(protectedOutcome.released());
        assertTrue(protectedOutcome.detail().contains("有效租期"));
        assertEquals(LeaseStatus.ACTIVE, s.service.get("later").getStatus());
        System.out.printf("[输入] now=%d 对未到期 later 执行 reclaim => %s%n",
                s.clock.millis(), protectedOutcome.detail());

        // 批量回收：仅 soon 被释放
        List<ReclaimOutcome> outcomes = s.service.reclaimExpired();
        assertEquals(1, outcomes.size());
        assertTrue(outcomes.get(0).released());
        assertTrue(s.reclaimer.isReleased(soon.getResourceId()));
        assertFalse(s.reclaimer.isReleased(later.getResourceId()));
        System.out.printf("[输入] reclaimExpired => %s%n", outcomes.get(0).detail());

        // 过期环境不残留：活跃表中查不到，历史中可解释
        assertThrows(LeaseException.class,
                () -> s.service.get("soon"));
        assertWaitReleasedReason(s, "soon");

        // 回收可重复执行：再来几轮无副作用，资源归还集合不重复
        List<ReclaimOutcome> second = s.service.reclaimExpired();
        assertTrue(second.isEmpty());
        ReclaimOutcome repeat = s.service.reclaim("soon");
        assertFalse(repeat.released());
        assertTrue(repeat.detail().contains("幂等") || repeat.detail().contains("已释放"));
        System.out.printf("[输入] 重复 reclaim soon => %s%n", repeat.detail());
    }

    private void assertWaitReleasedReason(LeaseTestSupport s, String name) {
        var rec = s.store.findReleased(name).orElseThrow();
        assertEquals("LEASE_EXPIRED", rec.getReason());
        System.out.printf("[断言] %s 已终结，释放历史原因=%s%n", name, rec.getReason());
    }

    @Test
    void renewAfterExpiryIsRejectedAndRenewBeforeExpiryWins() {
        var s = new LeaseTestSupport(tmp, 2, 10_000);
        s.service.create("env-a", "team-1", 10_000L);

        // 超过 deadline 后续租：确定结论 LEASE_EXPIRED，状态推进到 EXPIRED
        s.clock.advance(10_001);
        LeaseException ex = assertThrows(LeaseException.class,
                () -> s.service.renew("env-a", "team-1", 5_000L));
        assertEquals(RejectReason.LEASE_EXPIRED, ex.getReason());
        assertEquals(LeaseStatus.EXPIRED, s.service.get("env-a").getStatus());
        System.out.printf("[输入] now=%d 到期后续租 env-a => 拒绝原因=%s，状态推进 EXPIRED%n",
                s.clock.millis(), ex.getReason());

        // EXPIRED 之后的任何续租都被拒绝，回收可正常完成
        assertThrows(LeaseException.class, () -> s.service.renew("env-a", "team-1", 1L));
        assertTrue(s.service.reclaim("env-a").released());
    }

    @Test
    void renewExtendsDeadlineSoExpiryScanSkipsIt() {
        var s = new LeaseTestSupport(tmp, 2, 10_000);
        s.service.create("env-a", "team-1", 10_000L);
        // 在到期前一刻续租：deadline 顺延，到期扫描不得回收
        s.clock.advance(9_000);
        Lease renewed = s.service.renew("env-a", "team-1", 10_000L);
        assertEquals(1_020_000L, renewed.getDeadlineMillis()); // 以旧 deadline(1010000) 为基准 +10000
        s.clock.advance(1_500); // now=1010500，已超过原始 deadline 但在新 deadline 内
        List<ReclaimOutcome> outcomes = s.service.reclaimExpired();
        assertTrue(outcomes.isEmpty(), "续租生效后不得被到期扫描回收");
        assertEquals(LeaseStatus.ACTIVE, s.service.get("env-a").getStatus());
        System.out.printf("[输入] 续租后越过原 deadline，now=%d < newDeadline=%d，扫描跳过%n",
                s.clock.millis(), renewed.getDeadlineMillis());
    }

    @Test
    void reclaimFailureIsRetriedAndDoesNotBlockOthers() {
        var s = new LeaseTestSupport(tmp, 2, 10_000);
        var bad = s.service.create("bad", "team-1", 10_000L).lease();
        var good = s.service.create("good", "team-1", 10_000L).lease();
        // bad 的资源前 2 次归还失败
        s.reclaimer.failNextAttempts(bad.getResourceId(), 2);

        s.clock.advance(10_001);
        List<ReclaimOutcome> round1 = s.service.reclaimExpired();
        // 两个候选都被处理：bad 失败但不阻塞 good
        assertEquals(2, round1.size());
        Lease badAfter1 = s.service.get("bad");
        assertEquals(LeaseStatus.RECLAIM_FAILED, badAfter1.getStatus());
        assertEquals(1, badAfter1.getReclaimAttempts());
        assertNotNull(badAfter1.getLastErrorMessage());
        assertTrue(s.reclaimer.isReleased(good.getResourceId()));
        assertFalse(s.reclaimer.isReleased(bad.getResourceId()));
        System.out.printf("[输入] 第1轮回收：bad=>%s（attempts=%d, err=%s），good 正常释放%n",
                badAfter1.getStatus(), badAfter1.getReclaimAttempts(),
                badAfter1.getLastErrorMessage());

        // good 已释放、bad 回收失败仍占 1 个容量；在剩余配额内可以创建新环境
        assertTrue(s.service.create("third", "team-1", 10_000L).created(),
                "good 释放后容量应归还，可创建新环境");
        // 但失败的 bad 本身仍占着容量：配额再次占满，第四个应被拒
        try {
            s.service.create("fourth", "team-1", 10_000L);
            fail("回收失败的 bad 仍占资源，fourth 必须触发 QUOTA_EXCEEDED");
        } catch (LeaseException e) {
            assertEquals(RejectReason.QUOTA_EXCEEDED, e.getReason());
            System.out.printf("[断言] 回收失败的 bad 仍占容量：fourth 被拒原因=%s%n", e.getReason());
        }

        // 第二次重试仍失败，第三次成功（回收可重试且结论确定）
        ReclaimOutcome retry1 = s.service.reclaim("bad");
        assertFalse(retry1.released());
        assertEquals(2, s.service.get("bad").getReclaimAttempts());
        ReclaimOutcome retry2 = s.service.reclaim("bad");
        assertTrue(retry2.released());
        assertTrue(s.reclaimer.isReleased(bad.getResourceId()));
        System.out.printf("[输入] bad 重试：第2次=%s，第3次=%s%n", retry1.detail(), retry2.detail());

        // 成功后再回收为空操作；此时 bad 容量释放，fourth 可以补位
        ReclaimOutcome again = s.service.reclaim("bad");
        assertFalse(again.released());
        assertTrue(s.service.create("fourth", "team-1", 10_000L).created(),
                "bad 回收成功后容量应恢复");
    }

    @Test
    void clockRollbackCannotShortenLeaseOrReclaimActive() {
        var s = new LeaseTestSupport(tmp, 2, 10_000);
        Lease created = s.service.create("env-a", "team-1", 10_000L).lease();
        // 时钟异常回拨：续租基准取 max(deadline, now)，租期只增不减
        s.clock.advance(2_000);
        s.clock.rollback(500_000);
        Lease renewed = s.service.renew("env-a", "team-1", 10_000L);
        assertEquals(created.getDeadlineMillis() + 10_000, renewed.getDeadlineMillis());
        System.out.printf("[输入] 时钟回拨后续租：deadline %d -> %d（未被墙钟缩短）%n",
                created.getDeadlineMillis(), renewed.getDeadlineMillis());

        // 回拨期间绝不可能误判到期
        List<ReclaimOutcome> outcomes = s.service.reclaimExpired();
        assertTrue(outcomes.isEmpty());
        assertEquals(LeaseStatus.ACTIVE, s.service.get("env-a").getStatus());
    }
}
