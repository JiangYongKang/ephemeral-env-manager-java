package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

import org.springframework.stereotype.Component;

/**
 * 基于 {@link System#currentTimeMillis()} 的默认时钟。
 */
@Component
public class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
