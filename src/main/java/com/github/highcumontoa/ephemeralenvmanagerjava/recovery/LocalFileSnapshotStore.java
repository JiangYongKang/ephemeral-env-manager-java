package com.github.highcumontoa.ephemeralenvmanagerjava.recovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.ephemeralenvmanagerjava.config.EphemeralProperties;
import com.github.highcumontoa.ephemeralenvmanagerjava.domain.Lease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 基于本地 JSON 文件的快照存储（Jackson 序列化）。
 * 写入采用临时文件 + 原子移动，避免崩溃留下半截文件。
 */
@Component
public class LocalFileSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSnapshotStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final EphemeralProperties properties;

    public LocalFileSnapshotStore(EphemeralProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void save(List<Lease> leases) {
        Path path = Path.of(properties.getSnapshotPath());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), leases);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 快照失败不阻断业务，下次变更会再次落盘。
            log.warn("snapshot save failed path={} cause={}", path, e.getMessage());
        }
    }

    @Override
    public synchronized List<Lease> load() {
        Path path = Path.of(properties.getSnapshotPath());
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return mapper.readValue(path.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            // 快照损坏时按空状态恢复，保证服务可启动。
            log.warn("snapshot load failed, starting empty path={} cause={}", path, e.getMessage());
            return List.of();
        }
    }
}
