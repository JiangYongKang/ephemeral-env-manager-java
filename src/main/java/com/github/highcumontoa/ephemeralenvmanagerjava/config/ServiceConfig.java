package com.github.highcumontoa.ephemeralenvmanagerjava.config;

import com.github.highcumontoa.ephemeralenvmanagerjava.resource.LocalResourceReclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.resource.ResourceReclaimer;
import com.github.highcumontoa.ephemeralenvmanagerjava.service.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.service.ReclaimScheduler;
import com.github.highcumontoa.ephemeralenvmanagerjava.store.JsonFileLeaseStore;
import com.github.highcumontoa.ephemeralenvmanagerjava.store.LeaseStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.time.Clock;

/**
 * 服务装配：Clock（可被测试替换）、LeaseStore（JSON 文件持久化）、
 * ResourceReclaimer（纯本地模拟）、LeaseService、ReclaimScheduler。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(LeaseProperties.class)
public class ServiceConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public LeaseStore leaseStore(LeaseProperties props) {
        String path = props.getDataFile();
        if (path == null || path.isBlank()) {
            path = Path.of(System.getProperty("user.dir"), ".ephemeral-env", "leases.json").toString();
        }
        return new JsonFileLeaseStore(path);
    }

    @Bean
    public LocalResourceReclaimer localResourceReclaimer() {
        return new LocalResourceReclaimer();
    }

    @Bean
    public LeaseService leaseService(LeaseStore store, ResourceReclaimer reclaimer, Clock clock,
                                     LeaseProperties props) {
        return new LeaseService(store, reclaimer, clock,
                props.getMaxActiveLeases(), props.getDefaultTtlMillis());
    }

    @Bean
    public ReclaimScheduler reclaimScheduler(LeaseService service, LeaseProperties props) {
        return new ReclaimScheduler(service, props.isSchedulerEnabled(),
                props.getReclaimIntervalMillis());
    }
}
