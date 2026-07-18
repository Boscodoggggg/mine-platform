package com.bosco.mine.server.modules.mine.location;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.command.outbox.OutboxCommitNotifier;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 生成、幂等发布并分页提供定位热路径的不可变完整快照。
 */
@Service
public class LocationSnapshotService {

    private static final ErrorCode SITE_NOT_FOUND =
            new ErrorCode("MINE-LOCATION-404-001", "定位运行快照矿区不存在");
    private static final ErrorCode SNAPSHOT_NOT_FOUND =
            new ErrorCode("MINE-LOCATION-404-002", "定位运行快照不存在");
    private static final ErrorCode SNAPSHOT_INVALID =
            new ErrorCode("MINE-LOCATION-500-001", "定位运行快照无法生成");
    private static final int INSERT_BATCH_SIZE = 500;
    private static final int MAX_PAGE_SIZE = 1_000;
    private static final Pattern ITEM_KEY =
            Pattern.compile("^[A-Z][A-Z_]*:[0-9a-f-]{36}$");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final LocationSnapshotMapper mapper;
    private final ObjectMapper objectMapper;
    private final OutboxCommitNotifier commitNotifier;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建定位运行快照服务。
     *
     * @param mapper 快照源数据与不可变存储接口
     * @param objectMapper JSON 映射器
     * @param commitNotifier 事务提交后的精确 Outbox 通知器
     */
    public LocationSnapshotService(
            LocationSnapshotMapper mapper,
            ObjectMapper objectMapper,
            OutboxCommitNotifier commitNotifier
    ) {
        this.mapper = Objects.requireNonNull(mapper, "定位快照数据接口不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 映射器不能为空");
        this.commitNotifier = Objects.requireNonNull(commitNotifier, "Outbox 通知器不能为空");
    }

    /**
     * 在当前业务事务中生成定位快照和可靠刷新事件。
     *
     * <p>矿区行锁串行化修订分配。仅与最新快照内容完全相同时跳过；
     * 内容先变更后恢复会产生新修订，保证运行面能再次激活该内容。</p>
     *
     * @param organizationId 组织标识
     * @param siteId 矿区标识
     * @param reason 触发原因
     * @return 新建快照，连续相同内容时为空
     */
    @Transactional
    public Optional<SnapshotView> captureSite(
            UUID organizationId,
            UUID siteId,
            ChangeReason reason
    ) {
        return captureSite(organizationId, siteId, reason, idGenerator.next());
    }

    /**
     * 使用调用方预分配的 Outbox 标识生成定位快照。
     *
     * <p>换绑幂等台账需在修改关系前固定首次响应，因此可先预分配事件标识；
     * 快照、换绑、幂等响应和 Outbox 仍在同一事务中提交。</p>
     *
     * @param organizationId 组织标识
     * @param siteId 矿区标识
     * @param reason 触发原因
     * @param eventId 预分配 Outbox 事件标识
     * @return 新建快照，连续相同内容时为空
     */
    @Transactional
    public Optional<SnapshotView> captureSite(
            UUID organizationId,
            UUID siteId,
            ChangeReason reason,
            UUID eventId
    ) {
        Objects.requireNonNull(organizationId, "组织标识不能为空");
        Objects.requireNonNull(siteId, "矿区标识不能为空");
        Objects.requireNonNull(reason, "快照变更原因不能为空");
        Objects.requireNonNull(eventId, "Outbox 事件标识不能为空");
        mapper.lockSite(organizationId, siteId)
                .orElseThrow(() -> new MineException(SITE_NOT_FOUND, Map.of("siteId", siteId)));

        UUID snapshotId = idGenerator.next();
        List<LocationSnapshotMapper.SnapshotItemWrite> items = normalizeItems(
                snapshotId, mapper.findSourceItems(organizationId, siteId));
        String contentHash = snapshotHash(items);
        Optional<LocationSnapshotMapper.SnapshotRecord> latest = mapper.findLatest(siteId);
        if (latest.filter(snapshot -> snapshot.contentHash().equals(contentHash)).isPresent()) {
            return Optional.empty();
        }

        long revision = mapper.nextRevision(siteId);
        mapper.insertSnapshot(
                snapshotId, organizationId, siteId, revision,
                reason.name(), items.size(), contentHash);
        for (int start = 0; start < items.size(); start += INSERT_BATCH_SIZE) {
            mapper.insertItems(items.subList(start, Math.min(start + INSERT_BATCH_SIZE, items.size())));
        }

        LocationSnapshotOutboxEvent event = new LocationSnapshotOutboxEvent(
                eventId, snapshotId, siteId, revision, contentHash, currentTraceId(eventId));
        mapper.insertOutbox(
                event.eventId(), event.snapshotId(), writeJson(event.payload()),
                writeJson(Map.of(
                        "partitionKey", event.siteId().toString(),
                        "traceId", event.traceId())));
        commitNotifier.notifyAfterCommit(eventId);
        return Optional.of(new SnapshotView(
                snapshotId, organizationId, siteId, revision, reason,
                items.size(), contentHash, eventId));
    }

    /**
     * 读取指定矿区最新快照元数据。
     *
     * @param siteId 矿区标识
     * @return 最新快照元数据
     */
    @Transactional(readOnly = true)
    public SnapshotMetadata findLatest(UUID siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("矿区标识不能为空");
        }
        return mapper.findLatest(siteId)
                .map(LocationSnapshotService::metadata)
                .orElseThrow(() -> new MineException(
                        SNAPSHOT_NOT_FOUND, Map.of("siteId", siteId)));
    }

    /**
     * 使用条目键游标分页读取指定不可变快照。
     *
     * @param snapshotId 快照标识
     * @param afterItemKey 上一页最后条目键，首页为空
     * @param limit 单页数量
     * @return 快照头、当前条目和下一页游标
     */
    @Transactional(readOnly = true)
    public SnapshotPage findSnapshotPage(UUID snapshotId, String afterItemKey, int limit) {
        if (snapshotId == null || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("定位运行快照分页参数不合法");
        }
        String normalizedCursor = normalizeCursor(afterItemKey);
        LocationSnapshotMapper.SnapshotRecord snapshot = mapper.findSnapshot(snapshotId)
                .orElseThrow(() -> new MineException(
                        SNAPSHOT_NOT_FOUND, Map.of("snapshotId", snapshotId)));
        List<LocationSnapshotMapper.SnapshotItemRecord> fetched = mapper.findItems(
                snapshotId, normalizedCursor, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<LocationSnapshotMapper.SnapshotItemRecord> pageItems = hasMore
                ? fetched.subList(0, limit)
                : fetched;
        String nextAfterItemKey = hasMore
                ? pageItems.get(pageItems.size() - 1).itemKey()
                : null;
        return new SnapshotPage(
                snapshot.id(), snapshot.organizationId(), snapshot.siteId(),
                snapshot.revision(), ChangeReason.valueOf(snapshot.changeReason()),
                snapshot.itemCount(), snapshot.contentHash(), snapshot.createdAt(),
                pageItems.stream().map(item -> new SnapshotItemView(
                        item.itemKey(), item.itemType(), item.contentJson(), item.contentHash()))
                        .toList(),
                nextAfterItemKey);
    }

    private List<LocationSnapshotMapper.SnapshotItemWrite> normalizeItems(
            UUID snapshotId,
            List<LocationSnapshotMapper.SourceItemRecord> sourceItems
    ) {
        List<LocationSnapshotMapper.SourceItemRecord> sorted = sourceItems.stream()
                .sorted(Comparator.comparing(LocationSnapshotMapper.SourceItemRecord::itemKey))
                .toList();
        List<LocationSnapshotMapper.SnapshotItemWrite> items = new ArrayList<>(sorted.size());
        String previousKey = null;
        for (LocationSnapshotMapper.SourceItemRecord source : sorted) {
            if (source.itemKey().equals(previousKey) || !ITEM_KEY.matcher(source.itemKey()).matches()) {
                throw new MineException(SNAPSHOT_INVALID, Map.of("itemKey", source.itemKey()));
            }
            String canonicalJson = canonicalJson(source.contentJson());
            items.add(new LocationSnapshotMapper.SnapshotItemWrite(
                    snapshotId, source.itemKey(), source.itemType(),
                    canonicalJson, hash(canonicalJson)));
            previousKey = source.itemKey();
        }
        if (items.isEmpty()) {
            throw new MineException(SNAPSHOT_INVALID, Map.of("message", "矿区快照缺少基础条目"));
        }
        return List.copyOf(items);
    }

    private String canonicalJson(String json) {
        try {
            Map<String, Object> value = objectMapper.readValue(json, MAP_TYPE);
            return objectMapper.writeValueAsString(canonicalize(value));
        } catch (JacksonException exception) {
            throw new MineException(SNAPSHOT_INVALID, Map.of("message", "快照源 JSON 无法规范化"));
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }

    private String snapshotHash(List<LocationSnapshotMapper.SnapshotItemWrite> items) {
        StringBuilder canonical = new StringBuilder(items.size() * 120);
        for (LocationSnapshotMapper.SnapshotItemWrite item : items) {
            canonical.append(item.itemKey())
                    .append(':')
                    .append(item.contentHash())
                    .append('\n');
        }
        return hash(canonical.toString());
    }

    private String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new MineException(SNAPSHOT_INVALID, Map.of("message", "快照事件 JSON 无法序列化"));
        }
    }

    private String normalizeCursor(String afterItemKey) {
        if (afterItemKey == null || afterItemKey.isBlank()) {
            return null;
        }
        String normalized = afterItemKey.strip();
        if (!ITEM_KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException("定位运行快照游标不合法");
        }
        return normalized;
    }

    private String currentTraceId(UUID eventId) {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? eventId.toString() : traceId;
    }

    private static SnapshotMetadata metadata(LocationSnapshotMapper.SnapshotRecord snapshot) {
        return new SnapshotMetadata(
                snapshot.id(), snapshot.organizationId(), snapshot.siteId(), snapshot.revision(),
                ChangeReason.valueOf(snapshot.changeReason()), snapshot.itemCount(),
                snapshot.contentHash(), snapshot.createdAt());
    }

    /** 定位运行快照的稳定变更原因。 */
    public enum ChangeReason {
        SITE_CHANGE,
        ASSET_CHANGE,
        COORDINATE_CHANGE,
        BINDING_SWITCH,
        SPATIAL_CHANGE
    }

    /** 当前事务新建的定位运行快照。 */
    public record SnapshotView(
            UUID snapshotId,
            UUID organizationId,
            UUID siteId,
            long revision,
            ChangeReason changeReason,
            int itemCount,
            String contentHash,
            UUID outboxEventId
    ) {
    }

    /** 内部运行服务读取的最新快照元数据。 */
    public record SnapshotMetadata(
            UUID snapshotId,
            UUID organizationId,
            UUID siteId,
            long revision,
            ChangeReason changeReason,
            int itemCount,
            String contentHash,
            Instant createdAt
    ) {
    }

    /** 内部运行服务游标分页拉取的不可变快照页。 */
    public record SnapshotPage(
            UUID snapshotId,
            UUID organizationId,
            UUID siteId,
            long revision,
            ChangeReason changeReason,
            int itemCount,
            String contentHash,
            Instant createdAt,
            List<SnapshotItemView> items,
            String nextAfterItemKey
    ) {
        /** 冻结当前页条目。 */
        public SnapshotPage {
            items = List.copyOf(items);
        }
    }

    /** 定位运行快照中的单个规范化条目。 */
    public record SnapshotItemView(
            String itemKey,
            String itemType,
            String contentJson,
            String contentHash
    ) {
    }
}
