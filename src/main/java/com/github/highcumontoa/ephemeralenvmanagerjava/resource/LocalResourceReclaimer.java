package com.github.highcumontoa.ephemeralenvmanagerjava.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 纯本地实现的资源归还器：
 * <ul>
 *   <li>以集合记录已归还资源，同一 resourceId 重复调用直接跳过，保证回收幂等、可重复执行；</li>
 *   <li>可通过 {@link #failNextAttempts(String, int)} 为指定资源注入前 N 次失败，
 *       用于在本地完整验证"回收失败 → 记录原因 → 重试成功"而不依赖任何外部服务。</li>
 * </ul>
 */
public class LocalResourceReclaimer implements ResourceReclaimer {

    private static final Logger log = LoggerFactory.getLogger(LocalResourceReclaimer.class);

    private final Set<String> releasedResources = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Integer> remainingFailures = new ConcurrentHashMap<>();

    /** 令 resourceId 接下来的 attempts 次归还调用失败（可叠加），之后的调用正常归还。 */
    public void failNextAttempts(String resourceId, int attempts) {
        if (attempts > 0) {
            remainingFailures.merge(resourceId, attempts, Integer::sum);
        }
    }

    @Override
    public void release(String resourceId) {
        // 幂等短路：资源已归还，重复回收不产生任何副作用
        if (releasedResources.contains(resourceId)) {
            log.info("[resource] resource={} 已归还，重复回收跳过（幂等）", resourceId);
            return;
        }

        boolean[] failed = {false};
        remainingFailures.computeIfPresent(resourceId, (key, remaining) -> {
            if (remaining > 0) {
                failed[0] = true;
                return remaining - 1;
            }
            return null;
        });
        if (failed[0]) {
            log.warn("[resource] resource={} 归还失败（模拟），剩余注入失败次数已递减", resourceId);
            throw new IllegalStateException("simulated resource reclaim failure: " + resourceId);
        }

        releasedResources.add(resourceId);
        log.info("[resource] resource={} 归还成功，底层隔离环境资源已释放", resourceId);
    }

    public boolean isReleased(String resourceId) {
        return releasedResources.contains(resourceId);
    }
}
