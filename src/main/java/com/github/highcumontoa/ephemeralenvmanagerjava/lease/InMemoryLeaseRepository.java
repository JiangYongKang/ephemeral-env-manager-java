package com.github.highcumontoa.ephemeralenvmanagerjava.lease;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Repository
public class InMemoryLeaseRepository implements LeaseRepository {

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

    @Override
    public Lease create(String name, Function<Optional<Lease>, Lease> factory) {
        // compute 在 bin 锁内执行：名称判定、资源占用、映射写入原子完成，
        // 并发同名创建既不会双份占用，也不会在拒绝路径残留资源。
        AtomicReference<Lease> result = new AtomicReference<>();
        leases.compute(name, (key, current) -> {
            Lease next = factory.apply(Optional.ofNullable(current));
            result.set(next);
            return next;
        });
        return result.get();
    }

    @Override
    public Optional<Lease> find(String name) {
        return Optional.ofNullable(leases.get(name));
    }

    @Override
    public <T> T mutate(String name, LeaseMutator<T> mutator) {
        // bin 锁内的读-改-写：并发续租/状态迁移不会丢失更新。
        AtomicReference<T> result = new AtomicReference<>();
        leases.compute(name, (key, current) -> {
            if (current != null) {
                result.set(mutator.apply(current));
            }
            return current;
        });
        return result.get();
    }

    @Override
    public Collection<Lease> findAll() {
        return new ArrayList<>(leases.values());
    }

    @Override
    public void replaceAll(List<Lease> snapshot) {
        leases.clear();
        snapshot.forEach(lease -> leases.put(lease.getName(), lease));
    }
}
