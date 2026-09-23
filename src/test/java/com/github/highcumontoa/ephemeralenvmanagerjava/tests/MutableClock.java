package com.github.highcumontoa.ephemeralenvmanagerjava.tests;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Clock;

/**
 * 测试用可控时钟，可自由推进、回拨，模拟时钟异常与到期/续租竞争。
 */
public class MutableClock implements Clock {

    private volatile long now;

    public MutableClock(long start) {
        this.now = start;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void set(long millis) {
        this.now = millis;
    }

    public void advance(long deltaMillis) {
        this.now += deltaMillis;
    }
}
