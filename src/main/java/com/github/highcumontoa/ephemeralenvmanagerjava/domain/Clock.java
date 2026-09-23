package com.github.highcumontoa.ephemeralenvmanagerjava.domain;

/**
 * 可注入的时钟，便于测试时钟异常、续租与到期竞争。
 */
public interface Clock {
    long nowMillis();
}
