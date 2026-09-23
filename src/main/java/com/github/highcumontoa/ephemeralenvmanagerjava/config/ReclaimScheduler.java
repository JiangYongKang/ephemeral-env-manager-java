package com.github.highcumontoa.ephemeralenvmanagerjava.config;

import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性触发到期回收。
 */
@Component
public class ReclaimScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReclaimScheduler.class);

    private final Reclaimer reclaimer;

    public ReclaimScheduler(Reclaimer reclaimer) {
        this.reclaimer = reclaimer;
    }

    @Scheduled(fixedDelayString = "${ephemeral.reclaim-interval-millis:2000}")
    public void tick() {
        int reclaimed = reclaimer.reclaimExpired();
        if (reclaimed > 0) {
            log.info("scheduled reclaim pass reclaimed={}", reclaimed);
        }
    }
}
