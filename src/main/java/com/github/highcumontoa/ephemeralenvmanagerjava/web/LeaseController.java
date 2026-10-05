package com.github.highcumontoa.ephemeralenvmanagerjava.web;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.RejectReason;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseView;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.ReleaseRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.RenewRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ManualRetryResult;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.ReclaimProgress;
import com.github.highcumontoa.ephemeralenvmanagerjava.reclaim.Reclaimer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 临时环境租约 REST 接口。
 */
@RestController
@RequestMapping("/api/leases")
public class LeaseController {

    private final LeaseService service;
    private final Reclaimer reclaimer;

    public LeaseController(LeaseService service, Reclaimer reclaimer) {
        this.service = service;
        this.reclaimer = reclaimer;
    }

    @PostMapping
    public LeaseView acquire(@RequestBody CreateRequest request) {
        return LeaseView.of(service.acquire(request));
    }

    @PostMapping("/{name}/renew")
    public LeaseView renew(@PathVariable String name, @RequestBody RenewRequest request) {
        return LeaseView.of(service.renew(name, request));
    }

    @PostMapping("/{name}/release")
    public LeaseView release(@PathVariable String name,
                             @RequestBody(required = false) ReleaseRequest request) {
        return LeaseView.of(service.release(name, request));
    }

    @GetMapping("/{name}")
    public LeaseView get(@PathVariable String name) {
        Lease lease = service.get(name)
                .orElseThrow(() -> new LeaseException(RejectReason.NOT_FOUND,
                        "lease not found: " + name));
        return LeaseView.of(lease);
    }

    @GetMapping
    public List<LeaseView> list() {
        return service.list().stream().map(LeaseView::of).toList();
    }

    /**
     * 只读查询：所有“还没回收干净”的环境进度（等待回收/回收中/失败待重试/已升级兜底）。
     * 不取写锁、不改状态，不阻塞创建、续租与后台回收。
     */
    @GetMapping("/reclaim-pending")
    public List<ReclaimProgress> reclaimPending() {
        return reclaimer.listPendingReclaims();
    }

    /**
     * 手动触发一次回收重试（定点补救）。结果通过 outcome 字段明确区分：
     * 确实触发（RETRY_SUCCEEDED/RETRY_FAILED）、已归还干净（ALREADY_RELEASED）、
     * 不存在（NOT_FOUND）、不在回收阶段（NOT_IN_RECLAIM）。
     */
    @PostMapping("/{name}/reclaim-retry")
    public ManualRetryResult reclaimRetry(@PathVariable String name) {
        return reclaimer.manualRetry(name);
    }
}
