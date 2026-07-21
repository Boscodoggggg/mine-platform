package com.bosco.mine.telemetry.spatial;

import com.bosco.mine.telemetry.location.snapshot.LocationRuntimeRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在位置事实入队事务内固化精确空间快照，并按修订提供可重启恢复的只读区域目录。
 */
public final class SpatialRuntimeCatalog {

    private final JdbcTemplate jdbc;
    private final LocationRuntimeRegistry registry;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final ConcurrentHashMap<SnapshotKey, LocationRuntimeRegistry.RuntimeSnapshot> cache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SnapshotKey, PreparedSnapshot> preparedCache =
            new ConcurrentHashMap<>();

    /**
     * 创建共享 Telemetry 数据源和最后正确定位快照的空间运行目录。
     *
     * @param dataSource Telemetry 时序数据库
     * @param registry 最后正确定位运行快照注册表
     */
    public SpatialRuntimeCatalog(
            DataSource dataSource,
            LocationRuntimeRegistry registry
    ) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(
                dataSource, "空间运行目录数据源不能为空"));
        this.registry = Objects.requireNonNull(registry, "定位运行注册表不能为空");
    }

    /**
     * 固化入队事实解析时使用的精确区域修订，并计算所有生效区域的保守排序策略。
     *
     * <p>若该发生时间没有任何生效区域，则不创建高频队列记录；若解析后快照已切换，
     * 抛出异常让位置消息整体回滚并使用新快照重试，避免混用绑定和区域修订。</p>
     *
     * @param command 已解析位置事实的入队命令
     * @return 有生效区域时返回精确修订与最大迟到/空闲窗口
     */
    public Optional<SpatialEvaluationQueue.ResolvedPolicy> capture(
            SpatialEvaluationQueue.EnqueueCommand command
    ) {
        Objects.requireNonNull(command, "空间运行捕获命令不能为空");
        LocationRuntimeRegistry.RuntimeSnapshot snapshot = registry.current(command.siteId())
                .orElseThrow(() -> new IllegalStateException("空间运行捕获缺少定位快照"));
        if (!snapshot.organizationId().equals(command.organizationId())
                || snapshot.revision() != command.runtimeRevision()) {
            throw new IllegalStateException("位置解析后运行快照已切换，必须整体重试");
        }
        SnapshotKey key = new SnapshotKey(snapshot.siteId(), snapshot.revision());
        PreparedSnapshot prepared = prepare(key, snapshot);
        List<ZonePolicy> active = prepared.zonePolicies().stream()
                .filter(policy -> policy.contains(command.occurredAt()))
                .toList();
        if (active.isEmpty()) {
            return Optional.empty();
        }
        if (!cache.containsKey(key)) {
            persist(snapshot, prepared.zones());
            cacheAfterCommit(key, prepared.zoneSnapshot());
        }
        /*
         * 同一发布快照可能同时包含历史、当前和未来生效版本。若只采用当前版本策略，
         * 较激进的新版本会提前推进共享水位，导致边界前仍允许迟到的事实被误判。
         */
        int allowedLatenessSeconds = prepared.zonePolicies().stream()
                .mapToInt(ZonePolicy::allowedLatenessSeconds).max().orElseThrow();
        int idleFlushSeconds = prepared.zonePolicies().stream()
                .mapToInt(ZonePolicy::idleFlushSeconds).max().orElseThrow();
        return Optional.of(new SpatialEvaluationQueue.ResolvedPolicy(
                new SpatialWatermarkPolicy(
                        Duration.ofSeconds(allowedLatenessSeconds),
                        Duration.ofSeconds(idleFlushSeconds)),
                snapshot.revision()));
    }

    private PreparedSnapshot prepare(
            SnapshotKey key,
            LocationRuntimeRegistry.RuntimeSnapshot snapshot
    ) {
        PreparedSnapshot existing = preparedCache.get(key);
        if (existing != null) {
            existing.verifySource(snapshot);
            return existing;
        }
        List<LocationRuntimeRegistry.RuntimeItem> zones = snapshot.items().values().stream()
                .filter(this::isZone)
                .sorted(Comparator.comparing(LocationRuntimeRegistry.RuntimeItem::itemKey))
                .toList();
        PreparedSnapshot prepared = new PreparedSnapshot(
                snapshot.snapshotId(), snapshot.organizationId(), snapshot.itemCount(),
                snapshot.contentHash(), zones, zones.stream().map(this::zonePolicy).toList(),
                zoneSnapshot(snapshot, zones));
        PreparedSnapshot concurrent = preparedCache.putIfAbsent(key, prepared);
        PreparedSnapshot result = concurrent == null ? prepared : concurrent;
        result.verifySource(snapshot);
        return result;
    }

    private void cacheAfterCommit(
            SnapshotKey key,
            LocationRuntimeRegistry.RuntimeSnapshot snapshot
    ) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            cache.putIfAbsent(key, snapshot);
                        }
                    });
            return;
        }
        cache.putIfAbsent(key, snapshot);
    }

    /**
     * 按矿区和修订读取入队时固化的精确空间运行快照。
     *
     * @param siteId 矿区标识
     * @param runtimeRevision 位置事实入队时空间运行修订号
     * @return 精确修订不存在时返回空
     */
    public Optional<LocationRuntimeRegistry.RuntimeSnapshot> find(
            UUID siteId,
            long runtimeRevision
    ) {
        Objects.requireNonNull(siteId, "空间运行目录矿区标识不能为空");
        if (runtimeRevision < 1) {
            throw new IllegalArgumentException("空间运行目录修订号必须为正数");
        }
        SnapshotKey key = new SnapshotKey(siteId, runtimeRevision);
        LocationRuntimeRegistry.RuntimeSnapshot cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        List<SnapshotHeader> headers = jdbc.query("""
                SELECT organization_id, site_id, runtime_revision, snapshot_id,
                       zone_item_count, zone_content_hash, source_created_at
                FROM spatial_runtime_snapshot
                WHERE site_id = ? AND runtime_revision = ?
                """, (rs, rowNum) -> new SnapshotHeader(
                rs.getObject("organization_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getLong("runtime_revision"),
                rs.getObject("snapshot_id", UUID.class), rs.getInt("zone_item_count"),
                rs.getString("zone_content_hash"),
                rs.getObject("source_created_at", java.time.OffsetDateTime.class).toInstant()),
                siteId, runtimeRevision);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        SnapshotHeader header = headers.getFirst();
        List<LocationRuntimeRegistry.RuntimeItem> items = loadItems(siteId, runtimeRevision);
        verifyItems(header.zoneItemCount(), header.zoneContentHash(), items);
        LocationRuntimeRegistry.RuntimeSnapshot loaded = new LocationRuntimeRegistry.RuntimeSnapshot(
                header.snapshotId(), header.organizationId(), header.siteId(),
                header.runtimeRevision(), header.zoneItemCount(), header.zoneContentHash(),
                header.sourceCreatedAt(), index(items));
        LocationRuntimeRegistry.RuntimeSnapshot existing = cache.putIfAbsent(key, loaded);
        return Optional.of(existing == null ? loaded : existing);
    }

    private void persist(
            LocationRuntimeRegistry.RuntimeSnapshot snapshot,
            List<LocationRuntimeRegistry.RuntimeItem> zones
    ) {
        String zoneHash = combinedHash(zones);
        jdbc.update("""
                INSERT INTO spatial_runtime_snapshot (
                    organization_id, site_id, runtime_revision, snapshot_id,
                    zone_item_count, zone_content_hash, source_created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (site_id, runtime_revision) DO NOTHING
                """, snapshot.organizationId(), snapshot.siteId(), snapshot.revision(),
                snapshot.snapshotId(), zones.size(), zoneHash,
                java.sql.Timestamp.from(snapshot.createdAt()));
        SnapshotHeader persisted = jdbc.queryForObject("""
                SELECT organization_id, site_id, runtime_revision, snapshot_id,
                       zone_item_count, zone_content_hash, source_created_at
                FROM spatial_runtime_snapshot
                WHERE site_id = ? AND runtime_revision = ?
                """, (rs, rowNum) -> new SnapshotHeader(
                rs.getObject("organization_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getLong("runtime_revision"),
                rs.getObject("snapshot_id", UUID.class), rs.getInt("zone_item_count"),
                rs.getString("zone_content_hash"),
                rs.getObject("source_created_at", java.time.OffsetDateTime.class).toInstant()),
                snapshot.siteId(), snapshot.revision());
        if (persisted == null
                || !persisted.organizationId().equals(snapshot.organizationId())
                || !persisted.snapshotId().equals(snapshot.snapshotId())
                || persisted.zoneItemCount() != zones.size()
                || !persisted.zoneContentHash().equals(zoneHash)) {
            throw new IllegalStateException("相同空间运行修订对应了不同快照内容");
        }
        for (LocationRuntimeRegistry.RuntimeItem item : zones) {
            jdbc.update("""
                    INSERT INTO spatial_runtime_snapshot_item (
                        organization_id, site_id, runtime_revision,
                        item_key, item_type, content_json, content_hash
                    ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
                    ON CONFLICT (site_id, runtime_revision, item_key) DO NOTHING
                    """, snapshot.organizationId(), snapshot.siteId(), snapshot.revision(),
                    item.itemKey(), item.itemType(), item.contentJson(), item.contentHash());
        }
        verifyItems(zones.size(), zoneHash, loadItems(snapshot.siteId(), snapshot.revision()));
    }

    private List<LocationRuntimeRegistry.RuntimeItem> loadItems(
            UUID siteId,
            long runtimeRevision
    ) {
        return jdbc.query("""
                SELECT item_key, item_type, content_json::TEXT, content_hash
                FROM spatial_runtime_snapshot_item
                WHERE site_id = ? AND runtime_revision = ?
                ORDER BY item_key
                """, (rs, rowNum) -> new LocationRuntimeRegistry.RuntimeItem(
                rs.getString("item_key"), rs.getString("item_type"),
                rs.getString("content_json"), rs.getString("content_hash")),
                siteId, runtimeRevision);
    }

    private void verifyItems(
            int expectedCount,
            String expectedHash,
            List<LocationRuntimeRegistry.RuntimeItem> items
    ) {
        items.forEach(this::verifyItemContent);
        if (items.size() != expectedCount || !combinedHash(items).equals(expectedHash)) {
            throw new IllegalStateException("空间运行目录条目不完整或摘要不一致");
        }
    }

    /**
     * 重新规范化持久化 JSON 并复核单条摘要，防止内容被篡改而摘要列未同步更新。
     *
     * @param item 待复核的空间运行目录条目
     */
    private void verifyItemContent(LocationRuntimeRegistry.RuntimeItem item) {
        JsonNode content;
        try {
            content = jsonMapper.readTree(item.contentJson());
        } catch (JacksonException exception) {
            throw new IllegalStateException("空间运行目录条目 JSON 无法解析", exception);
        }
        if (content == null || !content.isObject()
                || !hash(canonicalJson(content)).equals(item.contentHash())) {
            throw new IllegalStateException("空间运行目录条目内容与摘要不一致");
        }
    }

    /**
     * 递归排序 JSON 对象键，保持数组顺序生成稳定字节序列。
     *
     * @param node JSON 内容
     * @return 规范化 JSON 文本
     */
    private String canonicalJson(JsonNode node) {
        try {
            Object value = jsonMapper.treeToValue(node, Object.class);
            return jsonMapper.writeValueAsString(canonicalize(value));
        } catch (JacksonException exception) {
            throw new IllegalStateException("空间运行目录条目无法规范化", exception);
        }
    }

    /**
     * 将 JSON 对象转换为键有序的递归结构。
     *
     * @param value JSON 通用对象
     * @return 可稳定序列化的对象
     */
    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }

    private LocationRuntimeRegistry.RuntimeSnapshot zoneSnapshot(
            LocationRuntimeRegistry.RuntimeSnapshot source,
            List<LocationRuntimeRegistry.RuntimeItem> zones
    ) {
        String zoneHash = combinedHash(zones);
        return new LocationRuntimeRegistry.RuntimeSnapshot(
                source.snapshotId(), source.organizationId(), source.siteId(), source.revision(),
                zones.size(), zoneHash, source.createdAt(), index(zones));
    }

    private Map<String, LocationRuntimeRegistry.RuntimeItem> index(
            List<LocationRuntimeRegistry.RuntimeItem> items
    ) {
        Map<String, LocationRuntimeRegistry.RuntimeItem> result = new LinkedHashMap<>();
        items.forEach(item -> result.put(item.itemKey(), item));
        return Map.copyOf(result);
    }

    private boolean isZone(LocationRuntimeRegistry.RuntimeItem item) {
        return "WAYPOINT_VERSION".equals(item.itemType())
                || "GEOFENCE_VERSION".equals(item.itemType());
    }

    private ZonePolicy zonePolicy(LocationRuntimeRegistry.RuntimeItem item) {
        JsonNode content;
        try {
            content = jsonMapper.readTree(item.contentJson());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("空间运行策略 JSON 无法解析", exception);
        }
        int allowed = requiredInt(content, "allowedLatenessSeconds");
        int idle = requiredInt(content, "idleFlushSeconds");
        if (allowed < 0 || allowed > 300 || idle < 1 || idle > 3_600 || idle <= allowed) {
            throw new IllegalArgumentException("空间运行迟到或空闲策略超出发布边界");
        }
        return new ZonePolicy(
                requiredInstant(content, "validFrom"), optionalInstant(content, "validTo"),
                allowed, idle);
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("空间运行策略缺少整数: " + field);
        }
        return value.intValue();
    }

    private Instant requiredInstant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("空间运行策略缺少时间: " + field);
        }
        try {
            return Instant.parse(value.asString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("空间运行策略时间不合法: " + field, exception);
        }
    }

    private Instant optionalInstant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : requiredInstant(node, field);
    }

    private String combinedHash(List<LocationRuntimeRegistry.RuntimeItem> items) {
        StringBuilder source = new StringBuilder();
        items.stream().sorted(Comparator.comparing(LocationRuntimeRegistry.RuntimeItem::itemKey))
                .forEach(item -> source.append(item.itemKey()).append(':')
                        .append(item.contentHash()).append('\n'));
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    /**
     * 计算与定位快照约定一致的 SHA-256 摘要。
     *
     * @param value 待计算文本
     * @return 带算法前缀的摘要
     */
    private String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    private record SnapshotKey(UUID siteId, long runtimeRevision) {
    }

    /** 已校验并编译策略、但不代表数据库事务已经提交的内存快照。 */
    private record PreparedSnapshot(
            UUID snapshotId,
            UUID organizationId,
            int sourceItemCount,
            String sourceContentHash,
            List<LocationRuntimeRegistry.RuntimeItem> zones,
            List<ZonePolicy> zonePolicies,
            LocationRuntimeRegistry.RuntimeSnapshot zoneSnapshot
    ) {
        private void verifySource(LocationRuntimeRegistry.RuntimeSnapshot source) {
            if (!snapshotId.equals(source.snapshotId())
                    || !organizationId.equals(source.organizationId())
                    || sourceItemCount != source.itemCount()
                    || !sourceContentHash.equals(source.contentHash())) {
                throw new IllegalStateException("同一空间运行修订对应了不同来源快照");
            }
        }
    }

    private record SnapshotHeader(
            UUID organizationId,
            UUID siteId,
            long runtimeRevision,
            UUID snapshotId,
            int zoneItemCount,
            String zoneContentHash,
            Instant sourceCreatedAt
    ) {
    }

    private record ZonePolicy(
            Instant validFrom,
            Instant validTo,
            int allowedLatenessSeconds,
            int idleFlushSeconds
    ) {
        private boolean contains(Instant occurredAt) {
            return !occurredAt.isBefore(validFrom)
                    && (validTo == null || occurredAt.isBefore(validTo));
        }
    }
}
