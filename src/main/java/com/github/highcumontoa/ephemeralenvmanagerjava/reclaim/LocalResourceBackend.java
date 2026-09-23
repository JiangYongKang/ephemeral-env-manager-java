package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于本地内存集合的资源后端，带容量上限与可配置的失败注入。
 * 不依赖任何外部服务、凭据或付费资源。
 */
@Component
public class LocalResourceBackend implements ResourceBackend {

    private final Set<String> allocated = ConcurrentHashMap.newKeySet();
    private final Set<String> failNextDeallocate = ConcurrentHashMap.newKeySet();
    private final EphemeralProperties properties;

    public LocalResourceBackend(EphemeralProperties properties) {
        this.properties = properties;
    }

    @Override
    public void allocate(String name) {
        // 容量判定与占用必须原子，避免并发创建击穿配额。
        synchronized (allocated) {
            if (allocated.contains(name)) {
                // 同一资源名重复占用视为幂等成功。
                return;
            }
            if (allocated.size() >= properties.getMaxActiveEnvironments()) {
                throw new ResourceException(
                        "capacity exceeded: allocated=" + allocated.size()
                                + ", capacity=" + properties.getMaxActiveEnvironments()
                                + ", env=" + name);
            }
            allocated.add(name);
        }
    }

    @Override
    public void deallocate(String name) {
        // 幂等归还：重复调用、归还不存在的资源都不产生副作用。
        // 失败注入：下一次对该资源的归还抛异常，之后恢复正常，用于模拟可重试故障。
        if (failNextDeallocate.remove(name)) {
            throw new ResourceException("injected transient deallocation failure: env=" + name);
        }
        allocated.remove(name);
    }

    @Override
    public int allocatedCount() {
        return allocated.size();
    }

    /** 测试支持：让下一次对 name 的归还失败一次。 */
    public void injectDeallocateFailureOnce(String name) {
        failNextDeallocate.add(name);
    }

    /** 测试支持：某资源当前是否仍被占用。 */
    public boolean isAllocated(String name) {
        return allocated.contains(name);
    }
}
