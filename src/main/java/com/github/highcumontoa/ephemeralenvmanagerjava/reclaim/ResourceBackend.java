package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

/**
 * 本地资源后端抽象：模拟真实隔离环境的占用与归还，不依赖任何外部服务。
 */
public interface ResourceBackend {

    /**
     * 占用底层资源。
     * @throws ResourceException 容量不足等
     */
    void allocate(String name);

    /**
     * 归还底层资源；必须幂等，可安全重复调用。
     */
    void deallocate(String name);

    /**
     * 当前已占用资源数。
     */
    int allocatedCount();
}
