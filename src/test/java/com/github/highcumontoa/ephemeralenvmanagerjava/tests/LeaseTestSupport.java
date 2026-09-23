package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.resource.LocalResourceReclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.service.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.store.JsonFileLeaseStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.store.LeaseStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试夹具：可控时钟（模拟时间流逝与时钟回拨）+ 临时 JSON 状态文件 + 本地模拟回收器。
 */
public final class LeaseTestSupport {

    /** 从固定起点开始的可控时钟，只随测试显式推进，不受宿主机墙钟影响。 */
    public static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        public long millis() {
            return millis.get();
        }

        public void advance(long deltaMillis) {
            millis.addAndGet(deltaMillis);
        }

        /** 时钟异常：人为回拨墙钟。 */
        public void rollback(long deltaMillis) {
            millis.addAndGet(-deltaMillis);
        }
    }

    public final MutableClock clock = new MutableClock();
    public final LocalResourceReclaimer reclaimer = new LocalResourceReclaimer();
    public final LeaseStore store;
    public final LeaseService service;
    public final Path dataFile;

    public LeaseTestSupport(Path tmpDir, int quota, long defaultTtlMillis) {
        this.dataFile = tmpDir.resolve("leases.json");
        this.store = new JsonFileLeaseStore(this.dataFile.toString());
        this.service = new LeaseService(store, reclaimer, clock, quota, defaultTtlMillis);
    }

    /** 用同一份状态文件构造"重启后"的新服务实例（恢复进度）。 */
    public LeaseService restartedService() {
        LeaseStore restored = new JsonFileLeaseStore(dataFile.toString());
        return new LeaseService(restored, reclaimer, clock, 2, 60_000);
    }

    public LeaseService restartedService(int quota, long defaultTtlMillis) {
        LeaseStore restored = new JsonFileLeaseStore(dataFile.toString());
        return new LeaseService(restored, reclaimer, clock, quota, defaultTtlMillis);
    }
}
