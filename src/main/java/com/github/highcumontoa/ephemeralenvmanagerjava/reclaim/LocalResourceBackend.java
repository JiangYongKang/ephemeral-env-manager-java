package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于本地内存集合的资源后端，带容量上限与可配置的失败注入。
 * 不依赖任何外部服务、凭据或付费资源。
 */
@Component
public class LocalResourceBackend implements ResourceBackend {

    private final Set<String> allocated = ConcurrentHashMap.newKeySet();
    private final Set<String> failNextDeallocate = ConcurrentHashMap.newKeySet();
    private final Set<String> failAlwaysDeallocate = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> deallocateCalls = new ConcurrentHashMap<>();
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
        // 失败注入：模拟可重试故障（一次性或持续性），用于测试重试与兜底。
        deallocateCalls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        if (failAlwaysDeallocate.contains(name)) {
            throw new ResourceException("injected persistent deallocation failure: env=" + name);
        }
        if (failNextDeallocate.remove(name)) {
            throw new ResourceException("injected transient deallocation failure: env=" + name);
        }
        allocated.remove(name);
    }

    @Override
    public void evict(String name) {
        // 仅从容量账目摘除：不受失败注入影响，幂等。
        synchronized (allocated) {
            allocated.remove(name);
        }
    }

    @Override
    public int allocatedCount() {
        return allocated.size();
    }

    /** 测试支持：让下一次对 name 的归还失败一次。 */
    public void injectDeallocateFailureOnce(String name) {
        failNextDeallocate.add(name);
    }

    /** 测试支持：让对 name 的归还持续失败，直到 {@link #clearDeallocateFailure(String)}。 */
    public void injectDeallocateFailureAlways(String name) {
        failAlwaysDeallocate.add(name);
    }

    /** 测试支持：清除对 name 的所有归还失败注入。 */
    public void clearDeallocateFailure(String name) {
        failAlwaysDeallocate.remove(name);
        failNextDeallocate.remove(name);
    }

    /** 测试支持：对 name 的归还接口被调用的累计次数（含失败注入导致的失败调用）。 */
    public int deallocateCallCount(String name) {
        AtomicInteger count = deallocateCalls.get(name);
        return count == null ? 0 : count.get();
    }

    /** 测试支持：某资源当前是否仍被占用。 */
    public boolean isAllocated(String name) {
        return allocated.contains(name);
    }
}
