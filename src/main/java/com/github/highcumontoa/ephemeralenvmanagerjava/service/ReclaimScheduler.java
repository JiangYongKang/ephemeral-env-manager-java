package com.github.highcumontoa.ephemeralenvmanagerjava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 周期性到期回收调度器。
 *
 * <p>默认关闭（{@code ephemeral-env.scheduler-enabled=false}），本地验证时可通过配置开启；
 * 每轮扫描调用 {@link LeaseService#reclaimExpired()}，单个环境失败已在服务层隔离。</p>
 */
public class ReclaimScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReclaimScheduler.class);

    private final LeaseService service;
    private final boolean enabled;

    public ReclaimScheduler(LeaseService service, boolean enabled, long intervalMillis) {
        this.service = service;
        this.enabled = enabled;
        log.info("[scheduler] 到期回收调度器: enabled={}, interval={}ms", enabled, intervalMillis);
    }

    /**
     * 固定间隔扫描；fixedDelay 取自配置属性，方法内再按开关短路，
     * 保证关闭时零行为、开启时每轮独立、异常不外泄到调度线程。
     */
    @Scheduled(fixedDelayString = "${ephemeral-env.reclaim-interval-millis:5000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            service.reclaimExpired();
        } catch (Exception e) {
            log.error("[scheduler] 本轮回收扫描出现未预期异常，已吞掉以保证后续轮次继续: {}",
                    e.toString(), e);
        }
    }
}
