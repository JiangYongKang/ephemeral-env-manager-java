package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.service.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.service.ReclaimScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回收调度器开关语义：关闭时 tick 零行为，开启时每轮触发一次扫描。
 */
class SchedulerTests {

    @TempDir
    Path tmp;

    /** 统计 reclaimExpired 调用次数的服务子类。 */
    private static class CountingService extends LeaseService {
        final AtomicInteger scans = new AtomicInteger();

        CountingService(LeaseTestSupport s) {
            super(s.store, s.reclaimer, s.clock, 2, 10_000);
        }

        @Override
        public java.util.List<com.github.highcumontoa.ephemeralenvmanagerjava.domain.ReclaimOutcome>
                reclaimExpired() {
            scans.incrementAndGet();
            return super.reclaimExpired();
        }
    }

    @Test
    void disabledSchedulerDoesNothingAndEnabledScans() {
        var s = new LeaseTestSupport(tmp, 2, 10_000);
        var counting = new CountingService(s);

        ReclaimScheduler disabled = new ReclaimScheduler(counting, false, 1L);
        for (int i = 0; i < 5; i++) {
            disabled.tick();
        }
        System.out.printf("[输入] 关闭状态下 tick 5 次，扫描次数=%d%n", counting.scans.get());
        assertEquals(0, counting.scans.get(), "调度器关闭时必须零行为");

        ReclaimScheduler enabled = new ReclaimScheduler(counting, true, 1L);
        counting.create("env-a", "team-1", 10_000L);
        s.clock.advance(10_001); // 到期
        enabled.tick();
        enabled.tick();
        System.out.printf("[输入] 开启状态下 tick 2 次，扫描次数=%d，env-a 到期后应已被首轮回收%n",
                counting.scans.get());
        assertEquals(2, counting.scans.get());
        assertTrue(s.store.findReleased("env-a").isPresent());
    }
}
