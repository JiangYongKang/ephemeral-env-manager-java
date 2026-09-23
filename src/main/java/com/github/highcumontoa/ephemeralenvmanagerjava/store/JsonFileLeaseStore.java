package com.github.highcumontoa.ephemeralenvmanagerjava.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.LeaseStatus;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.ReleasedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 基于单个 JSON 文件的 {@link LeaseStore} 实现。
 *
 * <p>语义保证：</p>
 * <ul>
 *   <li>进程内所有变更在同一把锁上串行化：同名并发的"检查-判断-写入"是一个原子区间，
 *       因此不会出现重复占用，也不会丢失更新；</li>
 *   <li>每次成功提交后全量快照写盘（临时文件 + 原子移动），崩溃不会产生半截文件；</li>
 *   <li>构造时从文件恢复：RELEASED 之外的全部状态（含 EXPIRED / RECLAIMING / RECLAIM_FAILED
 *       及回收尝试次数、最近失败原因）都会回到内存，待回收进度重启后可继续。</li>
 * </ul>
 */
public class JsonFileLeaseStore implements LeaseStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileLeaseStore.class);

    /** 磁盘上的完整快照。 */
    public static class SnapshotData {
        public Map<String, Lease> leases = new LinkedHashMap<>();
        public Map<String, ReleasedRecord> released = new LinkedHashMap<>();
    }

    private final Object lock = new Object();
    private final Path file;
    private final ObjectMapper mapper;
    private SnapshotData data;

    public JsonFileLeaseStore(String dataFilePath) {
        this.mapper = new ObjectMapper();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.file = Path.of(dataFilePath);
        this.data = load();
    }

    private SnapshotData load() {
        if (!Files.exists(file)) {
            log.info("[store] 状态文件不存在，以空状态启动: {}", file.toAbsolutePath());
            return new SnapshotData();
        }
        try {
            SnapshotData loaded = mapper.readValue(file.toFile(), SnapshotData.class);
            if (loaded.leases == null) {
                loaded.leases = new LinkedHashMap<>();
            }
            if (loaded.released == null) {
                loaded.released = new LinkedHashMap<>();
            }
            log.info("[store] 已从 {} 恢复状态：未终结租约 {} 个，已释放历史 {} 条",
                    file.toAbsolutePath(),
                    loaded.leases.values().stream().filter(l -> l.getStatus() != LeaseStatus.RELEASED).count(),
                    loaded.released.size());
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException("无法加载租约状态文件: " + file.toAbsolutePath(), e);
        }
    }

    @Override
    public Lease mutate(String name, Function<Optional<Lease>, Lease> fn) {
        synchronized (lock) {
            Lease existing = data.leases.get(name);
            Lease updated = fn.apply(Optional.ofNullable(existing));
            if (updated == REMOVE) {
                data.leases.remove(name);
                persistLocked();
                return null;
            }
            if (updated != null) {
                data.leases.put(name, updated);
                persistLocked();
                return copyOf(updated);
            }
            return existing == null ? null : copyOf(existing);
        }
    }

    @Override
    public Optional<Lease> find(String name) {
        synchronized (lock) {
            return Optional.ofNullable(data.leases.get(name)).map(JsonFileLeaseStore::copyOf);
        }
    }

    @Override
    public Optional<ReleasedRecord> findReleased(String name) {
        synchronized (lock) {
            return Optional.ofNullable(data.released.get(name)).map(JsonFileLeaseStore::copyOfRecord);
        }
    }

    @Override
    public void addReleased(ReleasedRecord record) {
        if (record == null || record.getName() == null) {
            return;
        }
        synchronized (lock) {
            // 同名的释放历史只保留首条（释放本身幂等，不重复记录）
            data.released.putIfAbsent(record.getName(), record);
            persistLocked();
        }
    }

    @Override
    public List<Lease> snapshot(Predicate<Lease> predicate) {
        synchronized (lock) {
            List<Lease> out = new ArrayList<>();
            for (Lease lease : data.leases.values()) {
                if (predicate.test(lease)) {
                    out.add(copyOf(lease));
                }
            }
            return out;
        }
    }

    @Override
    public int count(Predicate<Lease> predicate) {
        synchronized (lock) {
            int n = 0;
            for (Lease lease : data.leases.values()) {
                if (predicate.test(lease)) {
                    n++;
                }
            }
            return n;
        }
    }

    @Override
    public void flush() {
        synchronized (lock) {
            persistLocked();
        }
    }

    private void persistLocked() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("持久化租约状态失败: " + file.toAbsolutePath(), e);
        }
    }

    static Lease copyOf(Lease src) {
        Lease c = new Lease();
        c.setName(src.getName());
        c.setOwner(src.getOwner());
        c.setResourceId(src.getResourceId());
        c.setCreatedAtMillis(src.getCreatedAtMillis());
        c.setDeadlineMillis(src.getDeadlineMillis());
        c.setUpdatedAtMillis(src.getUpdatedAtMillis());
        c.setStatus(src.getStatus());
        c.setVersion(src.getVersion());
        c.setReclaimAttempts(src.getReclaimAttempts());
        c.setLastErrorMessage(src.getLastErrorMessage());
        return c;
    }

    private static ReleasedRecord copyOfRecord(ReleasedRecord src) {
        ReleasedRecord c = new ReleasedRecord();
        c.setName(src.getName());
        c.setOwner(src.getOwner());
        c.setReleasedAtMillis(src.getReleasedAtMillis());
        c.setReason(src.getReason());
        return c;
    }
}
