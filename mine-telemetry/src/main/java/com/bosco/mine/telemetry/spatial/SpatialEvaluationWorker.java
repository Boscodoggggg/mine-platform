package com.bosco.mine.telemetry.spatial;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.bosco.mine.model.spatial.SpatialPoint;
import com.bosco.mine.model.spatial.SpatialZone;
import com.bosco.mine.telemetry.location.snapshot.LocationRuntimeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按事件时间领取位置事实，并在单事务内推进区域状态、空间事件、Outbox 和队列状态。
 */
public final class SpatialEvaluationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpatialEvaluationWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RuntimeSnapshotSource snapshotSource;
    private final SpatialEvaluationQueue queue;
    private final SpatialEventStateMachine stateMachine = new SpatialEventStateMachine();
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final String workerId;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final Clock clock;
    private final SpatialEventMetrics metrics;
    private final ConcurrentHashMap<UUID, CachedZones> zoneCache = new ConcurrentHashMap<>();

    /**
     * 创建带数据库租约、失败退避和最后正确快照读取能力的空间评估器。
     *
     * @param dataSource Telemetry 时序数据库
     * @param registry 最后正确定位运行快照注册表
     * @param queue 空间事件时间排序队列
     * @param workerId 当前服务实例标识
     * @param leaseDuration 单条位置事实处理租约
     * @param maxAttempts 最大处理尝试次数
     * @param retryBaseDelay 首次失败退避时长
     * @param retryMaxDelay 最大失败退避时长
     */
    public SpatialEvaluationWorker(
            DataSource dataSource,
            LocationRuntimeRegistry registry,
            SpatialEvaluationQueue queue,
            String workerId,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay
    ) {
        this(dataSource, exactCurrentSource(registry), queue, workerId,
                leaseDuration, maxAttempts, retryBaseDelay, retryMaxDelay,
                Clock.systemUTC(), SpatialEventMetrics.noop());
    }

    /**
     * 创建从持久空间目录读取入队时精确运行修订的生产评估器。
     *
     * @param dataSource Telemetry 时序数据库
     * @param runtimeCatalog 精确空间运行快照目录
     * @param queue 空间事件时间排序队列
     * @param workerId 当前服务实例标识
     * @param leaseDuration 单条位置事实处理租约
     * @param maxAttempts 最大处理尝试次数
     * @param retryBaseDelay 首次失败退避时长
     * @param retryMaxDelay 最大失败退避时长
     */
    public SpatialEvaluationWorker(
            DataSource dataSource,
            SpatialRuntimeCatalog runtimeCatalog,
            SpatialEvaluationQueue queue,
            String workerId,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay
    ) {
        this(dataSource, Objects.requireNonNull(
                        runtimeCatalog, "空间运行目录不能为空")::find,
                queue, workerId, leaseDuration, maxAttempts,
                retryBaseDelay, retryMaxDelay, Clock.systemUTC(), SpatialEventMetrics.noop());
    }

    /**
     * 创建从持久空间目录读取精确修订并记录已提交业务事件的生产评估器。
     *
     * @param dataSource Telemetry 时序数据库
     * @param runtimeCatalog 精确空间运行快照目录
     * @param queue 空间事件时间排序队列
     * @param workerId 当前服务实例标识
     * @param leaseDuration 单条位置事实处理租约
     * @param maxAttempts 最大处理尝试次数
     * @param retryBaseDelay 首次失败退避时长
     * @param retryMaxDelay 最大失败退避时长
     * @param metrics 空间事件运行指标
     */
    public SpatialEvaluationWorker(
            DataSource dataSource,
            SpatialRuntimeCatalog runtimeCatalog,
            SpatialEvaluationQueue queue,
            String workerId,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            SpatialEventMetrics metrics
    ) {
        this(dataSource, Objects.requireNonNull(
                        runtimeCatalog, "空间运行目录不能为空")::find,
                queue, workerId, leaseDuration, maxAttempts,
                retryBaseDelay, retryMaxDelay, Clock.systemUTC(), metrics);
    }

    /**
     * 创建可注入可信时钟的空间评估器，供租约时间推进验证使用。
     *
     * @param dataSource Telemetry 时序数据库
     * @param registry 最后正确定位运行快照注册表
     * @param queue 空间事件时间排序队列
     * @param workerId 当前服务实例标识
     * @param leaseDuration 单条位置事实处理租约
     * @param maxAttempts 最大处理尝试次数
     * @param retryBaseDelay 首次失败退避时长
     * @param retryMaxDelay 最大失败退避时长
     * @param clock 平台可信时钟
     */
    public SpatialEvaluationWorker(
            DataSource dataSource,
            LocationRuntimeRegistry registry,
            SpatialEvaluationQueue queue,
            String workerId,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Clock clock
    ) {
        this(dataSource, exactCurrentSource(registry), queue, workerId,
                leaseDuration, maxAttempts, retryBaseDelay, retryMaxDelay, clock,
                SpatialEventMetrics.noop());
    }

    private SpatialEvaluationWorker(
            DataSource dataSource,
            RuntimeSnapshotSource snapshotSource,
            SpatialEvaluationQueue queue,
            String workerId,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Clock clock,
            SpatialEventMetrics metrics
    ) {
        Objects.requireNonNull(dataSource, "空间评估数据源不能为空");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.snapshotSource = Objects.requireNonNull(
                snapshotSource, "空间运行快照来源不能为空");
        this.queue = Objects.requireNonNull(queue, "空间评估队列不能为空");
        this.workerId = requireText(workerId, "空间评估实例标识");
        this.leaseDuration = requirePositive(leaseDuration, "空间评估租约");
        this.retryBaseDelay = requirePositive(retryBaseDelay, "空间评估基础退避");
        this.retryMaxDelay = requirePositive(retryMaxDelay, "空间评估最大退避");
        if (retryBaseDelay.compareTo(retryMaxDelay) > 0) {
            throw new IllegalArgumentException("空间评估基础退避不能大于最大退避");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("空间评估最大尝试次数必须在 1 到 100 之间");
        }
        this.maxAttempts = maxAttempts;
        this.clock = Objects.requireNonNull(clock, "空间评估时钟不能为空");
        this.metrics = Objects.requireNonNull(metrics, "空间事件运行指标不能为空");
    }

    /**
     * 有界处理当前已经越过水位或达到空闲刷新条件的位置事实。
     *
     * @param now 平台可信当前时间
     * @param limit 本轮最大处理数量
     * @return 本轮领取并成功或失败落账的记录数量
     */
    public int processReady(Instant now, int limit) {
        Objects.requireNonNull(now, "空间评估当前时间不能为空");
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("空间评估批量上限必须在 1 到 10000 之间");
        }
        int handled = 0;
        while (handled < limit) {
            Instant claimAt = currentTime(now);
            List<SpatialEvaluationQueue.ClaimedItem> claimed = queue.claimReady(
                    workerId, claimAt, leaseDuration, 1);
            if (claimed.isEmpty()) {
                break;
            }
            for (SpatialEvaluationQueue.ClaimedItem item : claimed) {
                processClaimed(item, claimAt);
                handled++;
            }
        }
        return handled;
    }

    private void processClaimed(SpatialEvaluationQueue.ClaimedItem item, Instant claimedAt) {
        try {
            List<EventMetric> emitted = transactions.execute(status -> {
                List<EventMetric> events = evaluate(item);
                Instant completedAt = currentTime(claimedAt);
                queue.markProcessed(
                        item.locationEventId(), workerId, item.leaseToken(), completedAt);
                return events;
            });
            Objects.requireNonNull(emitted, "空间评估事务结果不能为空").forEach(event ->
                    metrics.event(event.zoneType(), event.eventType(),
                            SpatialEventMetrics.Result.SUCCESS));
        } catch (RuntimeException exception) {
            Instant failedAt = currentTime(claimedAt);
            queue.markFailed(
                    item.locationEventId(), workerId, item.leaseToken(), failedAt,
                    retryBaseDelay, retryMaxDelay, maxAttempts, "EVALUATION_FAILED");
            LOGGER.warn(
                    "空间评估失败并已持久化退避状态: locationEventId={}, errorType={}",
                    item.locationEventId(), exception.getClass().getSimpleName());
        }
    }

    private List<EventMetric> evaluate(SpatialEvaluationQueue.ClaimedItem item) {
        List<EventMetric> emitted = new ArrayList<>();
        LocationRuntimeRegistry.RuntimeSnapshot snapshot = snapshotSource.find(
                        item.siteId(), item.runtimeRevision())
                .orElseThrow(() -> new IllegalStateException("空间评估缺少精确运行快照"));
        if (!snapshot.organizationId().equals(item.organizationId())
                || snapshot.revision() != item.runtimeRevision()) {
            throw new IllegalStateException("空间评估快照作用域或运行修订不一致");
        }
        PersistedLocationFact fact = loadFact(item);
        for (CompiledZone zone : zones(snapshot)) {
            if (!zone.contains(fact.occurredAt())) {
                continue;
            }
            Optional<SpatialEventStateMachine.ZoneState> prior = loadState(
                    item, zone.zoneType(), zone.configuration().zoneVersionId());
            if (prior.filter(state -> !isNewer(fact, state)).isPresent()) {
                continue;
            }
            SpatialEventStateMachine.TransitionResult transition = stateMachine.evaluate(
                    zone.configuration(), prior.orElse(null), fact.toStateMachineFact());
            persistState(item, zone, transition.state());
            for (SpatialEventStateMachine.EmittedEvent event : transition.events()) {
                persistEvent(item, fact, zone, transition, event);
                emitted.add(new EventMetric(
                        SpatialEventMetrics.ZoneType.valueOf(zone.zoneType()),
                        SpatialEventMetrics.EventType.valueOf(event.type().name())));
            }
        }
        return List.copyOf(emitted);
    }

    /** 已提交空间业务事件对应的固定低基数指标维度。 */
    private record EventMetric(
            SpatialEventMetrics.ZoneType zoneType,
            SpatialEventMetrics.EventType eventType
    ) {
    }

    private PersistedLocationFact loadFact(SpatialEvaluationQueue.ClaimedItem item) {
        List<PersistedLocationFact> facts = jdbc.query("""
                SELECT point.event_id, point.organization_id, point.site_id, point.tag_id,
                       point.vehicle_id, point.person_id, point.target_coordinate_system_id,
                       point.local_x, point.local_y, point.local_z,
                       point.quality_status, point.quality_reason,
                       point.time, point.ingest_sequence
                FROM location_point point
                WHERE point.event_id = ? AND point.time = ?
                  AND point.organization_id = ? AND point.site_id = ? AND point.tag_id = ?
                  AND point.ingest_sequence = ?
                """, this::mapFact, item.locationEventId(), sqlTime(item.occurredAt()),
                item.organizationId(), item.siteId(), item.tagId(), item.ingestSequence());
        if (facts.size() != 1) {
            throw new IllegalStateException("空间评估无法唯一读取锚点位置事实");
        }
        return facts.getFirst();
    }

    private PersistedLocationFact mapFact(ResultSet rs, int rowNumber) throws SQLException {
        return new PersistedLocationFact(
                rs.getObject("event_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getObject("tag_id", UUID.class),
                rs.getObject("vehicle_id", UUID.class),
                rs.getObject("person_id", UUID.class),
                rs.getObject("target_coordinate_system_id", UUID.class),
                rs.getDouble("local_x"), rs.getDouble("local_y"),
                nullableDouble(rs, "local_z"), rs.getString("quality_status"),
                rs.getString("quality_reason"),
                rs.getObject("time", java.time.OffsetDateTime.class).toInstant(),
                rs.getLong("ingest_sequence"));
    }

    private Optional<SpatialEventStateMachine.ZoneState> loadState(
            SpatialEvaluationQueue.ClaimedItem item,
            String zoneType,
            UUID zoneVersionId
    ) {
        return jdbc.query("""
                SELECT zone_version_id, stable_state, candidate_state, candidate_count,
                       entered_at, dwell_emitted_at, last_location_event_id,
                       last_occurred_at, last_ingest_sequence
                FROM spatial_zone_state
                WHERE organization_id = ? AND site_id = ? AND zone_type = ?
                  AND zone_version_id = ? AND tag_id = ?
                FOR UPDATE
                """, rs -> rs.next() ? Optional.of(mapState(rs)) : Optional.empty(),
                item.organizationId(), item.siteId(), zoneType, zoneVersionId, item.tagId());
    }

    private SpatialEventStateMachine.ZoneState mapState(ResultSet rs) throws SQLException {
        String candidate = rs.getString("candidate_state");
        return new SpatialEventStateMachine.ZoneState(
                rs.getObject("zone_version_id", UUID.class),
                SpatialEventStateMachine.StableState.valueOf(rs.getString("stable_state")),
                candidate == null ? null : SpatialEventStateMachine.StableState.valueOf(candidate),
                rs.getInt("candidate_count"), instantOrNull(rs, "entered_at"),
                instantOrNull(rs, "dwell_emitted_at"),
                rs.getObject("last_location_event_id", UUID.class),
                rs.getObject("last_occurred_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getLong("last_ingest_sequence"));
    }

    private boolean isNewer(
            PersistedLocationFact fact,
            SpatialEventStateMachine.ZoneState state
    ) {
        return fact.occurredAt().isAfter(state.lastOccurredAt())
                || (fact.occurredAt().equals(state.lastOccurredAt())
                && fact.ingestSequence() > state.lastIngestSequence());
    }

    private void persistState(
            SpatialEvaluationQueue.ClaimedItem item,
            CompiledZone zone,
            SpatialEventStateMachine.ZoneState state
    ) {
        int changed = jdbc.update("""
                INSERT INTO spatial_zone_state (
                    organization_id, site_id, zone_type, zone_version_id, tag_id,
                    stable_state, candidate_state, candidate_count,
                    entered_at, dwell_emitted_at, last_location_event_id,
                    last_occurred_at, last_ingest_sequence, runtime_revision,
                    state_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (site_id, zone_type, zone_version_id, tag_id) DO UPDATE SET
                    organization_id = EXCLUDED.organization_id,
                    stable_state = EXCLUDED.stable_state,
                    candidate_state = EXCLUDED.candidate_state,
                    candidate_count = EXCLUDED.candidate_count,
                    entered_at = EXCLUDED.entered_at,
                    dwell_emitted_at = EXCLUDED.dwell_emitted_at,
                    last_location_event_id = EXCLUDED.last_location_event_id,
                    last_occurred_at = EXCLUDED.last_occurred_at,
                    last_ingest_sequence = EXCLUDED.last_ingest_sequence,
                    runtime_revision = EXCLUDED.runtime_revision,
                    state_version = spatial_zone_state.state_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE (spatial_zone_state.last_occurred_at,
                       spatial_zone_state.last_ingest_sequence)
                    < (EXCLUDED.last_occurred_at, EXCLUDED.last_ingest_sequence)
                """, item.organizationId(), item.siteId(), zone.zoneType(),
                zone.configuration().zoneVersionId(), item.tagId(),
                state.stableState().name(),
                state.candidateState() == null ? null : state.candidateState().name(),
                state.candidateCount(), nullableTime(state.enteredAt()),
                nullableTime(state.dwellEmittedAt()), state.lastLocationEventId(),
                sqlTime(state.lastOccurredAt()), state.lastIngestSequence(),
                item.runtimeRevision());
        if (changed != 1) {
            throw new IllegalStateException("空间状态未能按事件时间单调推进");
        }
    }

    private void persistEvent(
            SpatialEvaluationQueue.ClaimedItem item,
            PersistedLocationFact fact,
            CompiledZone zone,
            SpatialEventStateMachine.TransitionResult transition,
            SpatialEventStateMachine.EmittedEvent event
    ) {
        RealtimeSpatialEvent.Evidence evidence = new RealtimeSpatialEvent.Evidence(
                fact.localX(), fact.localY(), fact.localZ(),
                Objects.requireNonNull(transition.classification()).name(),
                fact.qualityStatus(), fact.qualityReason(), zone.contentHash());
        String evidenceJson = canonicalJson(evidence);
        UUID proposedEventId = idGenerator.next();
        UUID eventId = jdbc.queryForObject("""
                INSERT INTO spatial_event (
                    event_id, organization_id, site_id, zone_type, zone_id,
                    zone_version_id, tag_id, vehicle_id, person_id, event_type,
                    anchor_location_event_id, previous_state, current_state,
                    confirmation_points, runtime_revision, occurred_at, evidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (
                    zone_type, zone_version_id, tag_id, event_type,
                    anchor_location_event_id
                ) DO UPDATE SET event_id = spatial_event.event_id
                RETURNING event_id
                """, UUID.class, proposedEventId, item.organizationId(), item.siteId(),
                zone.zoneType(), event.zoneId(), event.zoneVersionId(), event.tagId(),
                event.vehicleId(), event.personId(), event.type().name(),
                event.anchorLocationEventId(), event.previousState().name(),
                event.currentState().name(), event.confirmationPoints(),
                item.runtimeRevision(), sqlTime(event.occurredAt()), evidenceJson);
        RealtimeSpatialEvent payload = new RealtimeSpatialEvent(
                Objects.requireNonNull(eventId), item.organizationId(), item.siteId(),
                RealtimeSpatialEvent.ZoneType.valueOf(zone.zoneType()), event.zoneId(),
                event.zoneVersionId(), event.tagId(), event.vehicleId(), event.personId(),
                RealtimeSpatialEvent.EventType.valueOf(event.type().name()),
                event.anchorLocationEventId(),
                RealtimeSpatialEvent.StableState.valueOf(event.previousState().name()),
                RealtimeSpatialEvent.StableState.valueOf(event.currentState().name()),
                event.confirmationPoints(), fact.ingestSequence(), item.runtimeRevision(),
                event.occurredAt(), evidence);
        String payloadJson = canonicalJson(payload);
        jdbc.update("""
                INSERT INTO spatial_event_outbox (
                    event_id, event_type, schema_version, payload, payload_hash,
                    headers, status, attempts, next_attempt_at, occurred_at, created_at
                ) VALUES (?, 'realtime.spatial.event.created', 1, CAST(? AS JSONB), ?,
                          CAST(? AS JSONB), 'PENDING', 0, CURRENT_TIMESTAMP, ?,
                          CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, payloadJson, hash(payloadJson), canonicalJson(Map.of(
                "partitionKey", item.siteId().toString(),
                "tagId", item.tagId().toString(),
                "zoneId", event.zoneId().toString())), sqlTime(event.occurredAt()));
    }

    private List<CompiledZone> zones(LocationRuntimeRegistry.RuntimeSnapshot snapshot) {
        CachedZones cached = zoneCache.compute(snapshot.siteId(), (siteId, existing) ->
                existing != null && existing.snapshotId().equals(snapshot.snapshotId())
                        ? existing : new CachedZones(snapshot.snapshotId(), compileZones(snapshot)));
        return cached.zones();
    }

    private List<CompiledZone> compileZones(LocationRuntimeRegistry.RuntimeSnapshot snapshot) {
        List<CompiledZone> result = new ArrayList<>();
        snapshot.items().values().stream()
                .filter(item -> "WAYPOINT_VERSION".equals(item.itemType())
                        || "GEOFENCE_VERSION".equals(item.itemType()))
                .sorted(Comparator.comparing(LocationRuntimeRegistry.RuntimeItem::itemKey))
                .forEach(item -> result.add(compileZone(item)));
        return List.copyOf(result);
    }

    private CompiledZone compileZone(LocationRuntimeRegistry.RuntimeItem item) {
        JsonNode content = readObject(item.contentJson());
        UUID versionId = requiredUuid(content, "versionId");
        int enterConfirmPoints = requiredInt(content, "enterConfirmPoints");
        int exitConfirmPoints = requiredInt(content, "exitConfirmPoints");
        int dwellSeconds = requiredInt(content, "dwellSeconds");
        double tolerance = requiredDouble(content, "boundaryToleranceMeters");
        Double minZ = optionalDouble(content, "minZ");
        Double maxZ = optionalDouble(content, "maxZ");
        SpatialZone geometry;
        String zoneType;
        UUID zoneId;
        if ("WAYPOINT_VERSION".equals(item.itemType())) {
            zoneType = "WAYPOINT";
            zoneId = requiredUuid(content, "waypointId");
            geometry = new SpatialZone.Circle(
                    requiredDouble(content, "x"), requiredDouble(content, "y"),
                    requiredDouble(content, "radiusMeters"), tolerance, minZ, maxZ);
        } else {
            zoneType = "GEOFENCE";
            zoneId = requiredUuid(content, "geofenceId");
            List<SpatialZone.Vertex> vertices = new ArrayList<>();
            requiredArray(content, "vertices").stream()
                    .sorted(Comparator.comparingInt(vertex -> requiredInt(
                            vertex, "sequenceNumber")))
                    .forEach(vertex -> vertices.add(new SpatialZone.Vertex(
                            requiredDouble(vertex, "x"), requiredDouble(vertex, "y"))));
            geometry = new SpatialZone.Polygon(vertices, tolerance, minZ, maxZ);
        }
        return new CompiledZone(
                zoneType,
                new SpatialEventStateMachine.ZoneConfiguration(
                        zoneId, versionId, geometry,
                        enterConfirmPoints, exitConfirmPoints, dwellSeconds),
                requiredInstant(content, "validFrom"), optionalInstant(content, "validTo"),
                requiredText(content, "contentHash"));
    }

    private JsonNode readObject(String value) {
        try {
            JsonNode result = jsonMapper.readTree(value);
            if (result == null || !result.isObject()) {
                throw new IllegalArgumentException("空间快照条目内容必须是对象");
            }
            return result;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("空间快照条目 JSON 无法解析", exception);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("空间快照缺少必要文本字段: " + field);
        }
        return value.asString();
    }

    private UUID requiredUuid(JsonNode node, String field) {
        try {
            return UUID.fromString(requiredText(node, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("空间快照 UUID 字段不合法: " + field, exception);
        }
    }

    private Instant requiredInstant(JsonNode node, String field) {
        try {
            return Instant.parse(requiredText(node, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("空间快照时间字段不合法: " + field, exception);
        }
    }

    private Instant optionalInstant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : requiredInstant(node, field);
    }

    private double requiredDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("空间快照数值字段不合法: " + field);
        }
        return value.doubleValue();
    }

    private Double optionalDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : requiredDouble(node, field);
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("空间快照整数字段不合法: " + field);
        }
        return value.intValue();
    }

    private List<JsonNode> requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("空间快照数组字段不合法: " + field);
        }
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return result;
    }

    private String canonicalJson(Object value) {
        try {
            Object tree = jsonMapper.treeToValue(jsonMapper.valueToTree(value), Object.class);
            return jsonMapper.writeValueAsString(canonicalize(tree));
        } catch (JacksonException exception) {
            throw new IllegalStateException("空间事件载荷无法规范化", exception);
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

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp nullableTime(Instant value) {
        return value == null ? null : sqlTime(value);
    }

    private static Timestamp sqlTime(Instant value) {
        return Timestamp.from(value);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(label + "长度不合法");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return value;
    }

    private Instant currentTime(Instant floor) {
        Instant current = clock.instant();
        return current.isAfter(floor) ? current : floor;
    }

    private static RuntimeSnapshotSource exactCurrentSource(LocationRuntimeRegistry registry) {
        LocationRuntimeRegistry safeRegistry = Objects.requireNonNull(
                registry, "定位运行注册表不能为空");
        return (siteId, runtimeRevision) -> safeRegistry.current(siteId)
                .filter(snapshot -> snapshot.revision() == runtimeRevision);
    }

    /** 按矿区和修订读取不可变空间运行快照。 */
    @FunctionalInterface
    private interface RuntimeSnapshotSource {
        /**
         * @param siteId 矿区标识
         * @param runtimeRevision 精确运行修订号
         * @return 已固化的精确快照
         */
        Optional<LocationRuntimeRegistry.RuntimeSnapshot> find(
                UUID siteId, long runtimeRevision);
    }

    /** 已从位置历史事实唯一读取的空间判定输入。 */
    private record PersistedLocationFact(
            UUID eventId,
            UUID organizationId,
            UUID siteId,
            UUID tagId,
            UUID vehicleId,
            UUID personId,
            UUID coordinateSystemId,
            double localX,
            double localY,
            Double localZ,
            String qualityStatus,
            String qualityReason,
            Instant occurredAt,
            long ingestSequence
    ) {

        private SpatialEventStateMachine.LocationFact toStateMachineFact() {
            return new SpatialEventStateMachine.LocationFact(
                    eventId, tagId, vehicleId, personId,
                    new SpatialPoint(localX, localY, localZ),
                    SpatialEventStateMachine.LocationQuality.valueOf(qualityStatus),
                    occurredAt, ingestSequence);
        }
    }

    /** 已编译且带有效时间和不可变摘要的单个区域版本。 */
    private record CompiledZone(
            String zoneType,
            SpatialEventStateMachine.ZoneConfiguration configuration,
            Instant validFrom,
            Instant validTo,
            String contentHash
    ) {

        private boolean contains(Instant occurredAt) {
            return !occurredAt.isBefore(validFrom)
                    && (validTo == null || occurredAt.isBefore(validTo));
        }
    }

    /** 以快照标识隔离的矿区区域编译缓存。 */
    private record CachedZones(UUID snapshotId, List<CompiledZone> zones) {
    }
}
