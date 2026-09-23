package com.github.highcumontoa.ephemeralenvmanagerjava.lease;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 租约存储抽象。所有方法的并发语义由实现保证：
 * 并发同名创建/续租不得产生重复占用或丢失更新。
 */
public interface LeaseRepository {

    /**
     * 在名称级锁内原子完成“判定-占用-写入”。
     * factory 接收当前租约（不存在为空），返回最终要保存的租约；
     * factory 内抛出异常时映射保持不变（可用于 NAME_CONFLICT / QUOTA_EXCEEDED 拒绝）。
     */
    Lease create(String name, Function<Optional<Lease>, Lease> factory);

    Optional<Lease> find(String name);

    /**
     * 在名称级锁内执行读改写，保证续租等更新不丢失。
     */
    <T> T mutate(String name, LeaseMutator<T> mutator);

    Collection<Lease> findAll();

    /**
     * 以给定快照替换全部租约（仅启动恢复使用）。
     */
    void replaceAll(List<Lease> leases);

    @FunctionalInterface
    interface LeaseMutator<T> {
        T apply(Lease lease);
    }
}
