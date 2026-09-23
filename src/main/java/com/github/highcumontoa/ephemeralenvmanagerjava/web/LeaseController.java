package com.github.highcumontoa.ephemeralenvmanagerjava.web;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.CreateResult;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.LeaseException;
import com.github.highcumontoa.ephemeralenvmanagerjava.error.RejectReason;
import com.github.highcumontoa.ephemeralenvmanagerjava.service.LeaseService;
import com.github.highcumontoa.ephemeralenvmanagerjava.web.dto.CreateRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.web.dto.LeaseView;
import com.github.highcumontoa.ephemeralenvmanagerjava.web.dto.ReleaseRequest;
import com.github.highcumontoa.ephemeralenvmanagerjava.web.dto.RenewRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 临时环境租约 HTTP 接口：
 * POST /api/envs、POST /api/envs/{name}/renew、POST /api/envs/{name}/release、GET /api/envs/{name}。
 * 所有失败经 {@link GlobalExceptionHandler} 输出带可区分 reason 的错误体。
 */
@RestController
@RequestMapping("/api/envs")
public class LeaseController {

    private final LeaseService service;
    private final Clock clock;

    public LeaseController(LeaseService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
        requireBody(request, "请求体缺失");
        CreateResult result = service.create(request.getName(), request.getOwner(), request.getTtlMillis());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("created", result.created());
        body.put("reason", result.created() ? "CREATED" : "IDEMPOTENT_EXISTING");
        body.put("detail", result.reason());
        body.put("lease", view(result.lease()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @PostMapping("/{name}/renew")
    public Map<String, Object> renew(@PathVariable String name, @RequestBody RenewRequest request) {
        requireBody(request, "请求体缺失");
        Lease lease = service.renew(name, request.getOwner(), request.getExtendMillis());
        return Map.of("renewed", true, "lease", view(lease));
    }

    @PostMapping("/{name}/release")
    public Map<String, Object> release(@PathVariable String name, @RequestBody ReleaseRequest request) {
        requireBody(request, "请求体缺失");
        Lease lease = service.release(name, request.getOwner());
        return Map.of("released", true, "lease", view(lease));
    }

    @GetMapping("/{name}")
    public Map<String, Object> get(@PathVariable String name) {
        return Map.of("lease", view(service.get(name)));
    }

    private LeaseView view(Lease lease) {
        boolean expired = lease.getStatus() != null
                && lease.getStatus().name().equals("ACTIVE")
                && lease.getDeadlineMillis() <= clock.millis();
        return LeaseView.from(lease, expired);
    }

    private static void requireBody(Object body, String message) {
        if (body == null) {
            throw new LeaseException(RejectReason.INVALID_REQUEST, message);
        }
    }
}
