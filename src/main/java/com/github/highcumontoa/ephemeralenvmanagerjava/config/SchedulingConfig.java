package com.github.highcumontoa.ephemeralenvmanagerjava.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用后台定时回收。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
