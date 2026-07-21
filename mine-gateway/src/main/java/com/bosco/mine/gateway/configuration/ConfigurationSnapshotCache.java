package com.bosco.mine.gateway.configuration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 以有界 JSON 文件保存每个产品最后一次成功加载的完整配置快照。
 *
 * <p>写入使用同目录临时文件和原子替换，进程重启时先恢复并重新编译全部快照。缓存损坏会阻止节点
 * 静默带着未知配置启动，发布消息也只有在持久化成功后才允许确认。</p>
 */
public final class ConfigurationSnapshotCache implements ConfigurationSnapshotStore {

    private static final int CACHE_SCHEMA_VERSION = 1;
    private static final int MAX_CACHED_PRODUCTS = 1024;
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final Path cacheFile;
    private final int maxCacheBytes;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final AtomicReference<Map<UUID, ConfigurationSnapshot>> snapshots;

    /**
     * 打开本地配置快照缓存。
     *
     * @param cacheFile 缓存文件路径
     * @param maxCacheBytes 缓存文件最大字节数
     */
    public ConfigurationSnapshotCache(Path cacheFile, int maxCacheBytes) {
        this.cacheFile = Objects.requireNonNull(cacheFile, "配置快照缓存路径不能为空").toAbsolutePath().normalize();
        if (maxCacheBytes < 1024 || maxCacheBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("配置快照缓存大小上限必须在 1 KiB 到 64 MiB 之间");
        }
        this.maxCacheBytes = maxCacheBytes;
        this.snapshots = new AtomicReference<>(readExisting());
    }

    /**
     * 重新校验并恢复缓存中的全部最后可用配置。
     *
     * @param registry 新进程的协议快照注册表
     * @return 成功恢复的产品数量
     */
    public int restoreInto(ProtocolSnapshotRegistry registry) {
        Objects.requireNonNull(registry, "协议快照注册表不能为空");
        List<ConfigurationSnapshot> ordered = snapshots.get().values().stream()
                .sorted(Comparator.comparingLong(ConfigurationSnapshot::releaseSequence)
                        .thenComparing(snapshot -> snapshot.productId().toString()))
                .toList();
        ordered.forEach(registry::activate);
        return ordered.size();
    }

    /**
     * 原子保存一个产品的新最后可用配置。
     *
     * @param snapshot 已激活配置快照
     */
    @Override
    public synchronized void persist(ConfigurationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "配置快照不能为空");
        Map<UUID, ConfigurationSnapshot> current = snapshots.get();
        ConfigurationSnapshot existing = current.get(snapshot.productId());
        if (existing != null) {
            if (snapshot.releaseSequence() < existing.releaseSequence()) {
                throw new ConfigurationSnapshotCacheException("拒绝用低发布序号覆盖最后可用配置缓存");
            }
            if (snapshot.releaseSequence() == existing.releaseSequence()) {
                if (snapshot.releaseId().equals(existing.releaseId())
                        && snapshot.contentHash().equals(existing.contentHash())) {
                    return;
                }
                throw new ConfigurationSnapshotCacheException("缓存中相同发布序号对应不同配置");
            }
        }
        if (existing == null && current.size() >= MAX_CACHED_PRODUCTS) {
            throw new ConfigurationSnapshotCacheException("配置快照缓存中的产品数量超过上限");
        }
        Map<UUID, ConfigurationSnapshot> next = new LinkedHashMap<>(current);
        next.put(snapshot.productId(), snapshot);
        Map<UUID, ConfigurationSnapshot> immutable = Map.copyOf(next);
        writeAtomically(immutable);
        snapshots.set(immutable);
    }

    /** @return 当前缓存的产品数量 */
    public int size() {
        return snapshots.get().size();
    }

    private Map<UUID, ConfigurationSnapshot> readExisting() {
        if (!Files.exists(cacheFile, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (!Files.isRegularFile(cacheFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigurationSnapshotCacheException("配置快照缓存必须是普通文件");
        }
        try {
            long size = Files.size(cacheFile);
            if (size > maxCacheBytes) {
                throw new ConfigurationSnapshotCacheException("配置快照缓存超过允许的容量上限");
            }
            byte[] content = Files.readAllBytes(cacheFile);
            if (content.length > maxCacheBytes) {
                throw new ConfigurationSnapshotCacheException("配置快照缓存超过允许的容量上限");
            }
            CacheDocument document = jsonMapper.readValue(content, CacheDocument.class);
            if (document.schemaVersion() != CACHE_SCHEMA_VERSION || document.snapshots() == null) {
                throw new ConfigurationSnapshotCacheException("配置快照缓存版本或结构不受支持");
            }
            if (document.snapshots().size() > MAX_CACHED_PRODUCTS) {
                throw new ConfigurationSnapshotCacheException("配置快照缓存中的产品数量超过上限");
            }
            Map<UUID, ConfigurationSnapshot> restored = new LinkedHashMap<>();
            for (ConfigurationSnapshot snapshot : document.snapshots()) {
                if (snapshot == null || restored.putIfAbsent(snapshot.productId(), snapshot) != null) {
                    throw new ConfigurationSnapshotCacheException("配置快照缓存包含空值或重复产品");
                }
            }
            return Map.copyOf(restored);
        } catch (ConfigurationSnapshotCacheException exception) {
            throw exception;
        } catch (IOException | JacksonException | IllegalArgumentException exception) {
            throw new ConfigurationSnapshotCacheException("配置快照缓存无法读取", exception);
        }
    }

    private void writeAtomically(Map<UUID, ConfigurationSnapshot> next) {
        Path parent = cacheFile.getParent();
        if (parent == null) {
            throw new ConfigurationSnapshotCacheException("配置快照缓存路径缺少父目录");
        }
        Path temporary = null;
        try {
            byte[] content = jsonMapper.writeValueAsBytes(new CacheDocument(
                    CACHE_SCHEMA_VERSION,
                    next.values().stream()
                            .sorted(Comparator.comparing(snapshot -> snapshot.productId().toString()))
                            .toList()
            ));
            if (content.length > maxCacheBytes) {
                throw new ConfigurationSnapshotCacheException("配置快照缓存超过允许的容量上限");
            }
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, cacheFile.getFileName().toString(), ".tmp");
            setOwnerOnlyPermissions(temporary);
            Files.write(temporary, content);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveReplacing(temporary, cacheFile);
            setOwnerOnlyPermissions(cacheFile);
        } catch (ConfigurationSnapshotCacheException exception) {
            throw exception;
        } catch (IOException | JacksonException exception) {
            throw new ConfigurationSnapshotCacheException("配置快照缓存无法原子写入", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件清理由操作系统或下一次运维巡检兜底，不覆盖原始失败原因。
                }
            }
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void setOwnerOnlyPermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统不支持权限位，仍依赖挂载目录和进程账号隔离。
        }
    }

    /** 本地缓存文件结构。 */
    private record CacheDocument(int schemaVersion, List<ConfigurationSnapshot> snapshots) {
    }
}
