package com.bosco.mine.telemetry.location.snapshot;

import com.bosco.mine.messaging.location.LocationRuntimeChangedPayload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 分页拉取、独立校验并原子激活控制面不可变定位运行快照。
 */
public final class LocationSnapshotLoader {

    private static final Pattern ITEM_KEY =
            Pattern.compile("^[A-Z][A-Z_]*:[0-9a-f-]{36}$");
    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");
    /** 覆盖控制面允许的 10,000 个路线节点并限制单条内存占用。 */
    private static final int MAX_ITEM_BYTES = 2_097_152;

    private final LocationRuntimeRegistry registry;
    private final LocationSnapshotSource source;
    private final int pageSize;
    private final LocationSnapshotMetrics metrics;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建定位快照加载器。
     *
     * @param registry 最后正确运行快照注册表
     * @param source 受信任控制面快照源
     * @param pageSize 单次拉取条目数
     */
    public LocationSnapshotLoader(
            LocationRuntimeRegistry registry,
            LocationSnapshotSource source,
            int pageSize
    ) {
        this(registry, source, pageSize,
                new LocationSnapshotMetrics(new SimpleMeterRegistry()));
    }

    /**
     * 创建带固定阶段指标的定位快照加载器。
     *
     * @param registry 最后正确运行快照注册表
     * @param source 受信任控制面快照源
     * @param pageSize 单次拉取条目数
     * @param metrics 定位快照阶段指标
     */
    public LocationSnapshotLoader(
            LocationRuntimeRegistry registry,
            LocationSnapshotSource source,
            int pageSize,
            LocationSnapshotMetrics metrics
    ) {
        this.registry = Objects.requireNonNull(registry, "定位运行注册表不能为空");
        this.source = Objects.requireNonNull(source, "定位快照源不能为空");
        if (pageSize < 1 || pageSize > 1_000) {
            throw new IllegalArgumentException("定位快照页大小必须在 1 到 1000 之间");
        }
        this.pageSize = pageSize;
        this.metrics = Objects.requireNonNull(metrics, "定位快照指标不能为空");
    }

    /**
     * 加载并应用一个定位运行变更事件。
     *
     * @param eventId RabbitMQ 事件标识
     * @param expectedSiteId 信封分区声明的矿区标识
     * @param payload 快照定位与完整性声明
     */
    public void apply(
            UUID eventId,
            UUID expectedSiteId,
            LocationRuntimeChangedPayload payload
    ) {
        Objects.requireNonNull(eventId, "定位运行事件标识不能为空");
        Objects.requireNonNull(expectedSiteId, "定位运行事件矿区标识不能为空");
        Objects.requireNonNull(payload, "定位运行事件载荷不能为空");
        String cursor = null;
        String previousItemKey = null;
        int itemCount = 0;
        int pageCount = 0;
        StringBuilder digestInput = new StringBuilder();
        LocationRuntimeRegistry.SnapshotMetadata metadata = null;
        try {
            do {
                if (++pageCount > 2_000) {
                    throw new PermanentLocationSnapshotException("定位快照分页游标未能收敛");
                }
                LocationSnapshotSource.SnapshotPage page;
                try {
                    page = source.fetch(payload.snapshotId(), cursor, pageSize);
                    metrics.stage(LocationSnapshotMetrics.Stage.FETCH,
                            LocationSnapshotMetrics.Result.SUCCESS);
                } catch (RuntimeException exception) {
                    metrics.stage(LocationSnapshotMetrics.Stage.FETCH,
                            LocationSnapshotMetrics.Result.FAILURE);
                    throw exception;
                }
                LocationRuntimeRegistry.SnapshotMetadata pageMetadata = validateHeader(
                        page, expectedSiteId, payload);
                if (metadata == null) {
                    metadata = pageMetadata;
                } else if (!metadata.equals(pageMetadata)) {
                    throw new PermanentLocationSnapshotException("定位快照分页头不一致");
                }
                if (pageCount > metadata.itemCount() / pageSize + 2) {
                    throw new PermanentLocationSnapshotException("定位快照分页数量超出声明范围");
                }
                List<LocationRuntimeRegistry.StagedItem> staged = new ArrayList<>();
                for (LocationSnapshotSource.SnapshotItem item : page.items()) {
                    if (previousItemKey != null
                            && previousItemKey.compareTo(item.itemKey()) >= 0) {
                        throw new PermanentLocationSnapshotException(
                                "定位快照条目未按稳定键严格递增");
                    }
                    String canonicalJson = validateAndCanonicalize(item);
                    staged.add(new LocationRuntimeRegistry.StagedItem(
                            item.itemKey(), item.itemType(), canonicalJson, item.contentHash()));
                    digestInput.append(item.itemKey()).append(':')
                            .append(item.contentHash()).append('\n');
                    previousItemKey = item.itemKey();
                    itemCount++;
                    if (itemCount > metadata.itemCount()) {
                        throw new PermanentLocationSnapshotException(
                                "定位快照条目超过声明数量");
                    }
                }
                String next = page.nextAfterItemKey();
                if (next != null && (page.items().isEmpty()
                        || !next.equals(page.items().getLast().itemKey())
                        || next.equals(cursor))) {
                    throw new PermanentLocationSnapshotException("定位快照下一页游标不合法");
                }
                metrics.stage(LocationSnapshotMetrics.Stage.VERIFY,
                        LocationSnapshotMetrics.Result.SUCCESS);
                try {
                    registry.stage(metadata, List.copyOf(staged));
                    metrics.stage(LocationSnapshotMetrics.Stage.STAGE,
                            LocationSnapshotMetrics.Result.SUCCESS);
                } catch (RuntimeException exception) {
                    metrics.stage(LocationSnapshotMetrics.Stage.STAGE,
                            LocationSnapshotMetrics.Result.FAILURE);
                    throw exception;
                }
                cursor = next;
            } while (cursor != null);

            if (metadata == null || itemCount != metadata.itemCount()) {
                throw new PermanentLocationSnapshotException("定位快照条目数量不完整");
            }
            if (!hash(digestInput.toString()).equals(metadata.contentHash())) {
                throw new PermanentLocationSnapshotException("定位快照完整摘要不一致");
            }
            metrics.stage(LocationSnapshotMetrics.Stage.VERIFY,
                    LocationSnapshotMetrics.Result.SUCCESS);
            try {
                registry.activate(metadata);
                metrics.stage(LocationSnapshotMetrics.Stage.ACTIVATE,
                        LocationSnapshotMetrics.Result.SUCCESS);
            } catch (RuntimeException exception) {
                metrics.stage(LocationSnapshotMetrics.Stage.ACTIVATE,
                        LocationSnapshotMetrics.Result.FAILURE);
                throw exception;
            }
        } catch (PermanentLocationSnapshotException exception) {
            metrics.stage(LocationSnapshotMetrics.Stage.VERIFY,
                    LocationSnapshotMetrics.Result.FAILURE);
            registry.discard(payload.snapshotId());
            throw exception;
        }
    }

    private LocationRuntimeRegistry.SnapshotMetadata validateHeader(
            LocationSnapshotSource.SnapshotPage page,
            UUID expectedSiteId,
            LocationRuntimeChangedPayload payload
    ) {
        if (page == null || page.snapshotId() == null || page.organizationId() == null
                || page.siteId() == null || page.createdAt() == null
                || page.revision() < 1 || page.itemCount() < 1
                || page.contentHash() == null || !HASH.matcher(page.contentHash()).matches()
                || page.items().size() > pageSize
                || !payload.snapshotId().equals(page.snapshotId())
                || payload.revision() != page.revision()
                || !payload.contentHash().equals(page.contentHash())
                || !expectedSiteId.equals(page.siteId())) {
            throw new PermanentLocationSnapshotException(
                    "定位快照头与轻量事件或分区声明不一致");
        }
        return new LocationRuntimeRegistry.SnapshotMetadata(
                page.snapshotId(), page.organizationId(), page.siteId(), page.revision(),
                page.itemCount(), page.contentHash(), page.createdAt());
    }

    private String validateAndCanonicalize(LocationSnapshotSource.SnapshotItem item) {
        if (item == null || item.itemKey() == null
                || !ITEM_KEY.matcher(item.itemKey()).matches()
                || item.itemType() == null || item.itemType().isBlank()
                || item.contentHash() == null || !HASH.matcher(item.contentHash()).matches()
                || item.content() == null || !item.content().isObject()) {
            throw new PermanentLocationSnapshotException("定位快照条目格式不合法");
        }
        String canonicalJson = canonicalJson(item.content());
        if (canonicalJson.getBytes(StandardCharsets.UTF_8).length > MAX_ITEM_BYTES) {
            throw new PermanentLocationSnapshotException("定位快照条目超过大小上限");
        }
        if (!hash(canonicalJson).equals(item.contentHash())) {
            throw new PermanentLocationSnapshotException("定位快照单条内容摘要不一致");
        }
        return canonicalJson;
    }

    private String canonicalJson(JsonNode node) {
        try {
            Object value = jsonMapper.treeToValue(node, Object.class);
            return jsonMapper.writeValueAsString(canonicalize(value));
        } catch (JacksonException exception) {
            throw new PermanentLocationSnapshotException("定位快照条目无法规范化", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }
}
