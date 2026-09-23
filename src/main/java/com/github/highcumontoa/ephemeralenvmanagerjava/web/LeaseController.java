package com.github.highcumontoa.ephemeralenvmanagerjava.web;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.RejectReason;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseView;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.ReleaseRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.RenewRequest;
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

    public LeaseController(LeaseService service) {
        this.service = service;
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
}
