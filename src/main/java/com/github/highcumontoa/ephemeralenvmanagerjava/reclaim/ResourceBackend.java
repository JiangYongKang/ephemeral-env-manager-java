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
     * 仅从容量占用账目里摘除（兜底升级用），不执行真实归还、不抛异常。
     * 用于长期回收失败的环境：先把容量让出来给正常创建，真实归还留待后续重试。
     */
    void releaseAccounting(String name);

    /**
     * 当前已占用资源数。
     */
    int allocatedCount();
}
