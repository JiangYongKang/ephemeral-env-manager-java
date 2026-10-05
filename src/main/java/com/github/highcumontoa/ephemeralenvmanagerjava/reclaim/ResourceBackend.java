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
     * 卡死兜底：仅把资源从容量账目中摘除，使配额可被正常创建复用。
     * 不表达“资源已归还成功”的语义（底层资源可能仍有故障），必须幂等。
     */
    void evict(String name);

    /**
     * 当前已占用资源数。
     */
    int allocatedCount();
}
