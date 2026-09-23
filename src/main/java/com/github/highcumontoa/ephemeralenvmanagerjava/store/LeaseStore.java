package com.github.highcumontoa.ephemeralenvmanagerjava.store;

import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.ReleasedRecord;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 租约状态的持久化存储，保证：
 * <ul>
 *   <li>所有读改写操作原子执行（进程内串行化），杜绝丢失更新与重复占用；</li>
 *   <li>每次提交后落盘，服务重启后可恢复活跃租约与待回收进度。</li>
 * </ul>
 */
public interface LeaseStore {

    /** mutate 返回此哨兵表示从活跃表中删除该租约（终结）。 */
    Lease REMOVE = new Lease();

    /**
     * 原子的"读-改-写"：对名称为 name 的租约（不存在则 Optional.empty）应用 fn，
     * 返回非 null 且非 {@link #REMOVE} 则写入；返回 {@link #REMOVE} 则删除；
     * 返回 null 表示不变更。
     */
    Lease mutate(String name, Function<Optional<Lease>, Lease> fn);

    /** 原子读取单个活跃（非 RELEASED）租约的副本。 */
    Optional<Lease> find(String name);

    /** 原子查找已释放历史记录。 */
    Optional<ReleasedRecord> findReleased(String name);

    /** 原子追加一条已释放历史（同名只保留首条，保证释放幂等）。 */
    void addReleased(ReleasedRecord record);

    /** 原子扫描当前所有活跃租约中满足条件的副本。 */
    List<Lease> snapshot(Predicate<Lease> predicate);

    /** 原子统计满足条件的活跃租约数量。 */
    int count(Predicate<Lease> predicate);

    /** 强制把内存状态同步到磁盘。 */
    void flush();
}
