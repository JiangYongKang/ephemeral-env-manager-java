package com.github.highcumontoa.ephemeralenvmanagerjava.reclaim;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Clock;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseState;
import com.github.highcumontoa.ephemeralenvmanagerjava.lease.LeaseRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 回收进度只读查询：列出所有「还没回收干净」的租约
 * （等待回收的 EXPIRED、回收进行中的 RELEASING、失败待重试的 RELEASING、
 * 以及被兜底升级的 QUARANTINED）。
 *
 * 只读保证：不获取任何写锁、不调用任何变更方法、不写快照；
 * 基于 ConcurrentHashMap 的弱一致迭代，查询期间不阻塞创建/续租/后台回收，
 * 也不会改变任何租约状态。
 */
@Service
public class ReclaimQueryService {

    private final LeaseRepository repository;
    private final Clock clock;

    public ReclaimQueryService(LeaseRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<ReclaimProgressView> listUnfinished() {
        long now = clock.nowMillis();
        List<ReclaimProgressView> views = new java.util.ArrayList<>();
        for (Lease lease : repository.findAll()) {
            // 并发回收可能随时改变租约状态：状态只读一次并基于该次读取构建视图，
            // 保证弱一致快照下视图内部自洽、绝不抛异常。
            LeaseState observed = lease.getState();
            ReclaimPhase phase = phaseOf(observed, lease.getReleaseAttempts());
            if (phase == null) {
                continue; // ACTIVE / RELEASED：不属于「未回收干净」。
            }
            views.add(toView(lease, observed, phase, now));
        }
        views.sort(Comparator.comparing(ReclaimProgressView::name));
        return views;
    }

    private static ReclaimProgressView toView(Lease lease, LeaseState state,
                                              ReclaimPhase phase, long now) {
        // 旧快照可能缺少进入时刻：退化为创建时刻，保证停留时长可解释。
        long since = lease.getStateEnteredAtMillis() > 0
                ? lease.getStateEnteredAtMillis() : lease.getCreatedAtMillis();
        long stuck = Math.max(0, now - since);
        return new ReclaimProgressView(lease.getName(), lease.getOwner(), state,
                phase, lease.getReleaseAttempts(), lease.getLastFailureReason(),
                since, stuck);
    }

    /** @return 该状态对应的回收阶段；ACTIVE/RELEASED 返回 null（不属于查询范围）。 */
    private static ReclaimPhase phaseOf(LeaseState state, int releaseAttempts) {
        return switch (state) {
            case EXPIRED -> ReclaimPhase.WAITING_RECLAIM;
            case RELEASING -> releaseAttempts > 0
                    ? ReclaimPhase.RETRY_PENDING : ReclaimPhase.RECLAIMING;
            case QUARANTINED -> ReclaimPhase.QUARANTINED;
            case ACTIVE, RELEASED -> null;
        };
    }
}
