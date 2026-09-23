package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.CreateResult;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseStatus;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.RejectReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基础生命周期、容量配额、归属校验与可区分拒绝原因。
 */
class LeaseCoreTests {

    @TempDir
    Path tmp;

    private LeaseTestSupport support(int quota) {
        return new LeaseTestSupport(tmp, quota, 10_000);
    }

    private static LeaseException expectReject(Runnable action, RejectReason expected) {
        LeaseException ex = assertThrows(LeaseException.class, action::run);
        assertEquals(expected, ex.getReason(),
                "拒绝原因应可区分，期望 " + expected + " 实际 " + ex.getReason() + "：" + ex.getMessage());
        System.out.printf("[断言] 拒绝原因=%s 判定依据=%s%n", ex.getReason(), ex.getMessage());
        return ex;
    }

    @Test
    void createRenewReleaseHappyPath() {
        var s = support(2);
        CreateResult r1 = s.service.create("env-a", "team-1", 10_000L);
        assertTrue(r1.created());
        assertEquals(LeaseStatus.ACTIVE, r1.lease().getStatus());
        System.out.printf("[输入] create name=env-a owner=team-1 ttl=10000 => %s, deadline=%d%n",
                r1.reason(), r1.lease().getDeadlineMillis());

        // 有效期内续租：deadline 顺延且版本号单调递增（无丢失更新）
        Lease before = s.service.get("env-a");
        s.clock.advance(3_000);
        Lease renewed = s.service.renew("env-a", "team-1", 5_000L);
        assertEquals(before.getDeadlineMillis() + 5_000, renewed.getDeadlineMillis());
        assertEquals(before.getVersion() + 1, renewed.getVersion());
        System.out.printf("[输入] renew env-a +5000 at now=%d => deadline %d -> %d (version %d -> %d)%n",
                s.clock.millis(), before.getDeadlineMillis(), renewed.getDeadlineMillis(),
                before.getVersion(), renewed.getVersion());

        // 主动释放：资源归还、历史可查、名称可重新占用
        Lease released = s.service.release("env-a", "team-1");
        assertEquals(LeaseStatus.RELEASED, released.getStatus());
        assertTrue(s.reclaimer.isReleased(r1.lease().getResourceId()));
        System.out.printf("[输入] release env-a by team-1 => 资源 %s 已归还%n", r1.lease().getResourceId());

        expectReject(() -> s.service.get("env-a"), RejectReason.ALREADY_RELEASED);
        expectReject(() -> s.service.renew("env-a", "team-1", 1_000L), RejectReason.ALREADY_RELEASED);

        // 释放后同名可被（任意属主）重新占用，且是全新资源
        CreateResult r2 = s.service.create("env-a", "team-2", 10_000L);
        assertTrue(r2.created());
        assertNotEquals(r1.lease().getResourceId(), r2.lease().getResourceId());
        System.out.printf("[输入] 释放后重新创建 env-a by team-2 => 新资源 %s%n",
                r2.lease().getResourceId());
    }

    @Test
    void duplicateCreateIsIdempotentAndNameConflictRejected() {
        var s = support(2);
        CreateResult first = s.service.create("env-x", "team-1", 10_000L);
        CreateResult second = s.service.create("env-x", "team-1", 10_000L);
        assertFalse(second.created(), "同名同属主重复创建必须幂等，不得重复占用");
        assertEquals(first.lease().getResourceId(), second.lease().getResourceId());
        assertEquals(first.lease().getVersion(), second.lease().getVersion());
        System.out.printf("[输入] 重复 create env-x/team-1 => %s（资源、版本不变）%n", second.reason());

        expectReject(() -> s.service.create("env-x", "team-2", 10_000L),
                RejectReason.NAME_CONFLICT);
    }

    @Test
    void ownershipAndQuotaAndExistenceChecks() {
        var s = support(2);
        s.service.create("env-a", "team-1", 10_000L);
        s.service.create("env-b", "team-1", 10_000L);

        // 配额不足：第三个不同名称的环境被拒绝，原因可区分
        expectReject(() -> s.service.create("env-c", "team-1", 10_000L),
                RejectReason.QUOTA_EXCEEDED);

        // 非归属方续租与释放
        expectReject(() -> s.service.renew("env-a", "intruder", 1_000L), RejectReason.NOT_OWNER);
        expectReject(() -> s.service.release("env-a", "intruder"), RejectReason.NOT_OWNER);

        // 从未存在
        expectReject(() -> s.service.get("ghost"), RejectReason.NOT_FOUND);
        expectReject(() -> s.service.renew("ghost", "team-1", 1_000L), RejectReason.NOT_FOUND);
        expectReject(() -> s.service.release("ghost", "team-1"), RejectReason.NOT_FOUND);

        // 释放一个后容量立即可用
        s.service.release("env-b", "team-1");
        CreateResult c = s.service.create("env-c", "team-1", 10_000L);
        assertTrue(c.created());
        System.out.println("[输入] env-b 释放后创建 env-c 成功，容量被正确归还");

        // 对已释放租约的操作与"从未存在"区分开
        expectReject(() -> s.service.release("env-b", "team-1"), RejectReason.ALREADY_RELEASED);
    }

    @Test
    void invalidArgumentsRejected() {
        var s = support(2);
        expectReject(() -> s.service.create("", "team-1", 10_000L), RejectReason.INVALID_REQUEST);
        expectReject(() -> s.service.create("env-a", " ", 10_000L), RejectReason.INVALID_REQUEST);
        expectReject(() -> s.service.create("env-a", "team-1", 0L), RejectReason.INVALID_REQUEST);
        expectReject(() -> s.service.renew("env-a", "team-1", -1L), RejectReason.INVALID_REQUEST);
    }
}
