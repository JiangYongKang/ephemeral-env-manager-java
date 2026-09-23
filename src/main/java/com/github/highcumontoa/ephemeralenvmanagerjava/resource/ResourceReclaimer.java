package com.github.highcumontoa.ephemeralenvmanagerjava.resource;

/**
 * 底层隔离环境资源的本地模拟归还器。
 * 实现必须幂等：同一 resourceId 重复调用不得产生副作用。
 */
public interface ResourceReclaimer {

    /**
     * 归还资源；失败时抛出异常，调用方将记录失败原因并安排重试。
     */
    void release(String resourceId) throws Exception;
}
