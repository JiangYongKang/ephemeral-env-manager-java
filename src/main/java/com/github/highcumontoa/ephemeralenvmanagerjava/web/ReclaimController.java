package com.github.highcumontoa.ephemeralenvmanagerjava.web;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.RejectReason;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimProgressView;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimQueryService;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimRetryOutcome;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimRetryResult;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 回收运维接口：未回收干净租约的只读进度查询 + 单环境手动重试。
 */
@RestController
@RequestMapping("/api/leases")
public class ReclaimController {

    private final ReclaimQueryService queryService;
    private final Reclaimer reclaimer;

    public ReclaimController(ReclaimQueryService queryService, Reclaimer reclaimer) {
        this.queryService = queryService;
        this.reclaimer = reclaimer;
    }

    /**
     * 列出所有还没回收干净的环境（等待回收/回收中/失败待重试/已隔离）。
     * 只读：不阻塞也不改变任何生命周期动作。
     */
    @GetMapping("/reclaiming")
    public List<ReclaimProgressView> reclaiming() {
        return queryService.listUnfinished();
    }

    /**
     * 手动触发某个环境的回收重试。结果可区分：
     * TRIGGERED / NOT_DUE / ALREADY_RELEASED（200）与 NOT_FOUND（404）。
     */
    @PostMapping("/{name}/reclaim-retry")
    public ReclaimRetryResult retry(@PathVariable String name) {
        ReclaimRetryResult result = reclaimer.retryOne(name);
        if (result.outcome() == ReclaimRetryOutcome.NOT_FOUND) {
            throw new LeaseException(RejectReason.NOT_FOUND, "lease not found: " + name);
        }
        return result;
    }
}
