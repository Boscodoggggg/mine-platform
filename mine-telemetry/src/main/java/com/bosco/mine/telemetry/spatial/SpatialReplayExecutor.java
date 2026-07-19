package com.bosco.mine.telemetry.spatial;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.bosco.mine.model.spatial.SpatialPoint;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 使用数据库租约、固定源水位和持久游标分批执行显式空间历史重放请求。
 *
 * <p>重放状态按请求隔离，不会回退在线空间状态；每批事件、Outbox、状态和进度在
 * 同一事务提交。实例崩溃后，其他实例只能使用新租约令牌接管过期请求。</p>
 */
public final class SpatialReplayExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpatialReplayExecutor.class);
    /**
     * 状态预热与控制面允许的最大停留阈值对齐，避免短窗口重放无界扫描多年历史。
     */
    private static final Duration MAX_WARMUP_DURATION = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String instanceId;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final int batchSize;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final Clock clock;
    private final SpatialEventStateMachine stateMachine = new SpatialEventStateMachine();
    private final SpatialZoneCompiler zoneCompiler = new SpatialZoneCompiler();
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建可被多个 Telemetry 实例安全并发运行的历史重放执行器。
     *
     * @param dataSource Telemetry 时序数据库
     * @param instanceId 当前服务实例标识
     * @param leaseDuration 单次认领租约时长
     * @param maxAttempts 最大批次失败尝试次数
     * @param batchSize 单事务最大位置事实数
     * @param retryBaseDelay 首次失败退避时长
     * @param retryMaxDelay 最大失败退避时长
     * @param clock 平台可信时钟
     */
    public SpatialReplayExecutor(
            DataSource dataSource,
            String instanceId,
            Duration leaseDuration,
            int maxAttempts,
            int batchSize,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Clock clock
    ) {
        Objects.requireNonNull(dataSource, "空间重放数据源不能为空");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.instanceId = requireText(instanceId, "空间重放实例标识");
        this.leaseDuration = requirePositive(leaseDuration, "空间重放租约");
        this.retryBaseDelay = requirePositive(retryBaseDelay, "空间重放基础退避");
        this.retryMaxDelay = requirePositive(retryMaxDelay, "空间重放最大退避");
        if (retryBaseDelay.compareTo(retryMaxDelay) > 0) {
            throw new IllegalArgumentException("空间重放基础退避不能大于最大退避");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("空间重放最大尝试次数必须在 1 到 100 之间");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("空间重放批量上限必须在 1 到 10000 之间");
        }
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.clock = Objects.requireNonNull(clock, "空间重放时钟不能为空");
    }

    /**
     * 认领并执行一个请求的单个有界批次。
     *
     * @param now 本轮调度时间下界
     * @return 本轮处理结果；没有可执行请求时返回空闲结果
     */
    public BatchResult processNext(Instant now) {
        Objects.requireNonNull(now, "空间重放当前时间不能为空");
        Instant operationAt = currentTime(now);
        Optional<ReplayLease> claimed = claim(operationAt);
        if (claimed.isEmpty()) {
            return BatchResult.idle();
        }
        ReplayLease lease = claimed.get();
        try {
            return Objects.requireNonNull(transactions.execute(
                    status -> processBatch(lease, currentTime(operationAt))));
        } catch (RuntimeException exception) {
            markFailed(lease, currentTime(operationAt), exception);
            LOGGER.warn(
                    "空间历史重放批次失败并已持久化退避状态: requestId={}, errorType={}",
                    lease.requestId(), exception.getClass().getSimpleName());
            return new BatchResult(true, lease.requestId(), 0, 0, false, true);
        }
    }

    private Optional<ReplayLease> claim(Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> {
            terminalizeExhausted(now);
            List<ReplayRequest> candidates = jdbc.query("""
                    SELECT request_id, organization_id, site_id, zone_type, zone_version_id,
                           window_start, window_end, status, attempts, lease_token,
                           locked_by, lease_expires_at, source_watermark,
                           source_snapshot::TEXT, cursor_time, cursor_ingest_sequence,
                           zone_item_type, zone_content::TEXT, zone_content_hash,
                           zone_runtime_revision
                    FROM spatial_replay_request
                    WHERE ((status IN ('PENDING', 'FAILED')
                                AND attempts < ? AND next_attempt_at <= ?)
                           OR (status = 'PROCESSING'
                                AND ((locked_by = ? AND lease_expires_at > ?)
                                     OR (lease_expires_at <= ? AND attempts < ?))))
                    ORDER BY CASE
                               WHEN status = 'PROCESSING' AND locked_by = ?
                                    AND lease_expires_at > ? THEN 0
                               ELSE 1
                             END,
                             created_at, request_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """, this::mapRequest, maxAttempts, sqlTime(now), instanceId,
                    sqlTime(now), sqlTime(now), maxAttempts,
                    instanceId, sqlTime(now));
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            ReplayRequest request = candidates.getFirst();
            boolean continueOwnLease = "PROCESSING".equals(request.status())
                    && instanceId.equals(request.lockedBy())
                    && request.leaseExpiresAt() != null
                    && request.leaseExpiresAt().isAfter(now);
            UUID leaseToken = continueOwnLease ? request.leaseToken() : UUID.randomUUID();
            int attempts = continueOwnLease ? request.attempts() : request.attempts() + 1;
            long sourceWatermark = request.sourceWatermark() == null
                    ? sourceWatermark() : request.sourceWatermark();
            String sourceSnapshot = request.sourceSnapshot() == null
                    ? jdbc.queryForObject("SELECT pg_current_snapshot()::TEXT", String.class)
                    : request.sourceSnapshot();
            int changed = jdbc.update("""
                    UPDATE spatial_replay_request
                    SET status = 'PROCESSING', attempts = ?, next_attempt_at = ?,
                        lease_token = ?, locked_by = ?, locked_at = ?, lease_expires_at = ?,
                        source_watermark = ?, source_snapshot = CAST(? AS PG_SNAPSHOT),
                        started_at = COALESCE(started_at, ?),
                        last_error = NULL
                    WHERE request_id = ?
                    """, attempts, sqlTime(now), leaseToken, instanceId, sqlTime(now),
                    sqlTime(now.plus(leaseDuration)), sourceWatermark, sourceSnapshot, sqlTime(now),
                    request.requestId());
            if (changed != 1) {
                throw new IllegalStateException("空间重放请求认领结果不唯一");
            }
            return Optional.of(new ReplayLease(
                    request.requestId(), leaseToken, attempts));
        }));
    }

    /**
     * 在认领之前原子收敛已耗尽重试的失败任务和过期租约。
     *
     * <p>只删除本事务已确实转入 DEAD 的请求状态，不会跨越新租约围栏。</p>
     *
     * @param now 本轮调度时间
     */
    private void terminalizeExhausted(Instant now) {
        List<UUID> exhausted = jdbc.query("""
                WITH exhausted AS (
                    SELECT request_id, status
                    FROM spatial_replay_request
                    WHERE (status = 'FAILED' AND attempts >= ?)
                       OR (status = 'PROCESSING' AND attempts >= ?
                           AND lease_expires_at <= ?)
                    ORDER BY created_at, request_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 100
                )
                UPDATE spatial_replay_request replay
                SET status = 'DEAD', lease_token = NULL, locked_by = NULL,
                    locked_at = NULL, lease_expires_at = NULL, completed_at = ?,
                    last_error = CASE exhausted.status
                        WHEN 'PROCESSING' THEN 'REPLAY_DEAD:LEASE_EXPIRED'
                        ELSE 'REPLAY_DEAD:RETRY_EXHAUSTED'
                    END
                FROM exhausted
                WHERE replay.request_id = exhausted.request_id
                RETURNING replay.request_id
                """, (rs, rowNumber) -> rs.getObject("request_id", UUID.class),
                maxAttempts, maxAttempts, sqlTime(now), sqlTime(now));
        for (UUID requestId : exhausted) {
            jdbc.update("DELETE FROM spatial_replay_subject_state WHERE request_id = ?",
                    requestId);
        }
    }

    private long sourceWatermark() {
        Long result = jdbc.queryForObject(
                "SELECT last_value FROM location_ingest_sequence_seq", Long.class);
        return result == null ? 0L : result;
    }

    private BatchResult processBatch(ReplayLease lease, Instant now) {
        ReplayRequest request = loadOwnedRequest(lease);
        ReplayZone zone = loadZone(request, lease);
        List<ReplayFact> available = loadFacts(request, zone.compiled(), batchSize + 1);
        boolean completed = available.size() <= batchSize;
        List<ReplayFact> batch = completed ? available : available.subList(0, batchSize);
        int emittedEvents = 0;
        for (ReplayFact fact : batch) {
            if (!zone.compiled().contains(fact.occurredAt())) {
                continue;
            }
            SpatialEventStateMachine.ZoneState prior = loadState(request, fact.tagId())
                    .orElse(null);
            SpatialEventStateMachine.TransitionResult transition = stateMachine.evaluate(
                    zone.compiled().configuration(), prior, fact.toStateMachineFact());
            persistState(request, fact.tagId(), transition.state());
            if (!fact.occurredAt().isBefore(request.windowStart())) {
                for (SpatialEventStateMachine.EmittedEvent event : transition.events()) {
                    emittedEvents += persistEvent(request, fact, zone, transition, event);
                }
            }
        }
        ReplayFact last = batch.isEmpty() ? null : batch.getLast();
        Instant commitAt = currentTime(now);
        int changed;
        if (completed) {
            changed = jdbc.update("""
                    UPDATE spatial_replay_request
                    SET status = 'COMPLETED', scanned_points = scanned_points + ?,
                        emitted_events = emitted_events + ?,
                        cursor_time = COALESCE(?, cursor_time),
                        cursor_ingest_sequence = COALESCE(?, cursor_ingest_sequence),
                        lease_token = NULL, locked_by = NULL, locked_at = NULL,
                        lease_expires_at = NULL, completed_at = ?, last_error = NULL
                    WHERE request_id = ? AND status = 'PROCESSING'
                      AND locked_by = ? AND lease_token = ?
                    """, batch.size(), emittedEvents,
                    last == null ? null : sqlTime(last.occurredAt()),
                    last == null ? null : last.ingestSequence(), sqlTime(commitAt),
                    request.requestId(), instanceId, lease.leaseToken());
            jdbc.update("DELETE FROM spatial_replay_subject_state WHERE request_id = ?",
                    request.requestId());
        } else {
            changed = jdbc.update("""
                    UPDATE spatial_replay_request
                    SET scanned_points = scanned_points + ?,
                        emitted_events = emitted_events + ?, cursor_time = ?,
                        cursor_ingest_sequence = ?, locked_at = ?, lease_expires_at = ?
                    WHERE request_id = ? AND status = 'PROCESSING'
                      AND locked_by = ? AND lease_token = ?
                    """, batch.size(), emittedEvents, sqlTime(last.occurredAt()),
                    last.ingestSequence(), sqlTime(commitAt),
                    sqlTime(commitAt.plus(leaseDuration)),
                    request.requestId(), instanceId, lease.leaseToken());
        }
        if (changed != 1) {
            throw new IllegalStateException("空间重放租约已变化，拒绝提交旧执行结果");
        }
        return new BatchResult(
                true, request.requestId(), batch.size(), emittedEvents, completed, false);
    }

    private ReplayRequest loadOwnedRequest(ReplayLease lease) {
        List<ReplayRequest> requests = jdbc.query("""
                SELECT request_id, organization_id, site_id, zone_type, zone_version_id,
                       window_start, window_end, status, attempts, lease_token,
                       locked_by, lease_expires_at, source_watermark,
                       source_snapshot::TEXT, cursor_time, cursor_ingest_sequence,
                       zone_item_type, zone_content::TEXT, zone_content_hash,
                       zone_runtime_revision
                FROM spatial_replay_request
                WHERE request_id = ? AND status = 'PROCESSING'
                  AND locked_by = ? AND lease_token = ?
                FOR UPDATE
                """, this::mapRequest, lease.requestId(), instanceId, lease.leaseToken());
        if (requests.size() != 1) {
            throw new IllegalStateException("空间重放租约已变化，无法继续执行");
        }
        return requests.getFirst();
    }

    private ReplayZone loadZone(ReplayRequest request, ReplayLease lease) {
        String itemType = request.zoneType() + "_VERSION";
        String itemKey = itemType + ":" + request.zoneVersionId();
        ZoneSource source;
        if (request.zoneContent() != null) {
            source = new ZoneSource(
                    request.zoneItemType(), request.zoneContent(),
                    request.zoneContentHash(), request.zoneRuntimeRevision());
        } else {
            List<ZoneSource> sources = jdbc.query("""
                    SELECT stage.item_type, stage.content_json::TEXT, stage.content_hash,
                           runtime.runtime_revision
                    FROM location_runtime_revision runtime
                    JOIN location_snapshot_stage stage
                      ON stage.snapshot_id = runtime.snapshot_id
                     AND stage.organization_id = runtime.organization_id
                     AND stage.site_id = runtime.site_id
                     AND stage.runtime_revision = runtime.runtime_revision
                    WHERE runtime.organization_id = ? AND runtime.site_id = ?
                      AND stage.item_key = ?
                    """, (rs, rowNumber) -> new ZoneSource(
                    rs.getString("item_type"), rs.getString("content_json"),
                    rs.getString("content_hash"), rs.getLong("runtime_revision")),
                    request.organizationId(), request.siteId(), itemKey);
            if (sources.size() != 1) {
                throw new IllegalStateException("空间重放缺少当前已校验区域发布版本");
            }
            source = sources.getFirst();
        }
        String canonicalContent = canonicalJson(source.contentJson());
        if (!itemType.equals(source.itemType())
                || source.runtimeRevision() == null || source.runtimeRevision() < 1
                || !hash(canonicalContent).equals(source.contentHash())) {
            throw new IllegalStateException("空间重放区域发布版本内容与摘要不一致");
        }
        SpatialZoneCompiler.CompiledZone compiled = zoneCompiler.compile(
                source.itemType(), canonicalContent);
        if (!request.zoneType().equals(compiled.zoneType())
                || !request.zoneVersionId().equals(compiled.configuration().zoneVersionId())) {
            throw new IllegalStateException("空间重放区域发布版本身份不一致");
        }
        if (request.zoneContent() == null) {
            int changed = jdbc.update("""
                    UPDATE spatial_replay_request
                    SET zone_item_type = ?, zone_content = CAST(? AS JSONB),
                        zone_content_hash = ?, zone_runtime_revision = ?
                    WHERE request_id = ? AND status = 'PROCESSING'
                      AND locked_by = ? AND lease_token = ?
                      AND zone_content IS NULL
                    """, source.itemType(), canonicalContent, source.contentHash(),
                    source.runtimeRevision(), request.requestId(), instanceId,
                    lease.leaseToken());
            if (changed != 1) {
                throw new IllegalStateException("空间重放区域快照冻结失败或租约已变化");
            }
        }
        return new ReplayZone(compiled, source.runtimeRevision());
    }

    private List<ReplayFact> loadFacts(
            ReplayRequest request,
            SpatialZoneCompiler.CompiledZone zone,
            int limit
    ) {
        String cursorClause = request.cursorTime() == null ? "" : """
                  AND (point.time, point.ingest_sequence) > (?, ?)
                """;
        Instant upperBound = zone.validTo() == null || request.windowEnd().isBefore(zone.validTo())
                ? request.windowEnd() : zone.validTo();
        String sql = """
                SELECT point.event_id, point.organization_id, point.site_id, point.tag_id,
                       point.vehicle_id, point.person_id, point.local_x, point.local_y,
                       point.local_z, point.quality_status, point.quality_reason,
                       point.time, point.ingest_sequence
                FROM location_point point
                WHERE point.organization_id = ? AND point.site_id = ?
                  AND point.time >= ? AND point.time < ?
                  AND point.ingest_sequence <= ?
                  AND pg_visible_in_snapshot(
                        point.xmin::TEXT::XID8, CAST(? AS PG_SNAPSHOT))
                """ + cursorClause + """
                ORDER BY point.time, point.ingest_sequence
                LIMIT ?
                """;
        if (request.cursorTime() == null) {
            Instant warmupStart = request.windowStart().minus(MAX_WARMUP_DURATION);
            Instant lowerBound = zone.validFrom().isAfter(warmupStart)
                    ? zone.validFrom() : warmupStart;
            return jdbc.query(sql, this::mapFact, request.organizationId(), request.siteId(),
                    sqlTime(lowerBound), sqlTime(upperBound),
                    request.sourceWatermark(), request.sourceSnapshot(), limit);
        }
        return jdbc.query(sql, this::mapFact, request.organizationId(), request.siteId(),
                sqlTime(zone.validFrom()), sqlTime(upperBound),
                request.sourceWatermark(), request.sourceSnapshot(),
                sqlTime(request.cursorTime()),
                request.cursorIngestSequence(), limit);
    }

    private Optional<SpatialEventStateMachine.ZoneState> loadState(
            ReplayRequest request,
            UUID tagId
    ) {
        return jdbc.query("""
                SELECT stable_state, candidate_state, candidate_count,
                       entered_at, dwell_emitted_at, last_location_event_id,
                       last_occurred_at, last_ingest_sequence
                FROM spatial_replay_subject_state
                WHERE request_id = ? AND tag_id = ?
                FOR UPDATE
                """, rs -> rs.next() ? Optional.of(new SpatialEventStateMachine.ZoneState(
                request.zoneVersionId(),
                SpatialEventStateMachine.StableState.valueOf(rs.getString("stable_state")),
                nullableState(rs.getString("candidate_state")),
                rs.getInt("candidate_count"), instantOrNull(rs, "entered_at"),
                instantOrNull(rs, "dwell_emitted_at"),
                rs.getObject("last_location_event_id", UUID.class),
                rs.getObject("last_occurred_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getLong("last_ingest_sequence"))) : Optional.empty(),
                request.requestId(), tagId);
    }

    private void persistState(
            ReplayRequest request,
            UUID tagId,
            SpatialEventStateMachine.ZoneState state
    ) {
        jdbc.update("""
                INSERT INTO spatial_replay_subject_state (
                    request_id, organization_id, site_id, tag_id,
                    stable_state, candidate_state, candidate_count,
                    entered_at, dwell_emitted_at, last_location_event_id,
                    last_occurred_at, last_ingest_sequence, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (request_id, tag_id) DO UPDATE SET
                    stable_state = EXCLUDED.stable_state,
                    candidate_state = EXCLUDED.candidate_state,
                    candidate_count = EXCLUDED.candidate_count,
                    entered_at = EXCLUDED.entered_at,
                    dwell_emitted_at = EXCLUDED.dwell_emitted_at,
                    last_location_event_id = EXCLUDED.last_location_event_id,
                    last_occurred_at = EXCLUDED.last_occurred_at,
                    last_ingest_sequence = EXCLUDED.last_ingest_sequence,
                    updated_at = CURRENT_TIMESTAMP
                """, request.requestId(), request.organizationId(), request.siteId(), tagId,
                state.stableState().name(),
                state.candidateState() == null ? null : state.candidateState().name(),
                state.candidateCount(), nullableTime(state.enteredAt()),
                nullableTime(state.dwellEmittedAt()), state.lastLocationEventId(),
                sqlTime(state.lastOccurredAt()), state.lastIngestSequence());
    }

    private int persistEvent(
            ReplayRequest request,
            ReplayFact fact,
            ReplayZone zone,
            SpatialEventStateMachine.TransitionResult transition,
            SpatialEventStateMachine.EmittedEvent event
    ) {
        RealtimeSpatialEvent.Evidence evidence = new RealtimeSpatialEvent.Evidence(
                fact.localX(), fact.localY(), fact.localZ(),
                Objects.requireNonNull(transition.classification()).name(),
                fact.qualityStatus(), fact.qualityReason(), zone.compiled().contentHash());
        UUID eventId = idGenerator.next();
        List<UUID> inserted = jdbc.query("""
                INSERT INTO spatial_event (
                    event_id, organization_id, site_id, zone_type, zone_id,
                    zone_version_id, tag_id, vehicle_id, person_id, event_type,
                    anchor_location_event_id, previous_state, current_state,
                    confirmation_points, runtime_revision, occurred_at, evidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (
                    zone_type, zone_version_id, tag_id, event_type,
                    anchor_location_event_id
                ) DO NOTHING
                RETURNING event_id
                """, (rs, rowNumber) -> rs.getObject("event_id", UUID.class),
                eventId, request.organizationId(), request.siteId(), zone.compiled().zoneType(),
                event.zoneId(), event.zoneVersionId(), event.tagId(), event.vehicleId(),
                event.personId(), event.type().name(), event.anchorLocationEventId(),
                event.previousState().name(), event.currentState().name(),
                event.confirmationPoints(), zone.runtimeRevision(),
                sqlTime(event.occurredAt()), canonicalJson(evidence));
        if (inserted.isEmpty()) {
            return 0;
        }
        RealtimeSpatialEvent payload = new RealtimeSpatialEvent(
                eventId, request.organizationId(), request.siteId(),
                RealtimeSpatialEvent.ZoneType.valueOf(zone.compiled().zoneType()), event.zoneId(),
                event.zoneVersionId(), event.tagId(), event.vehicleId(), event.personId(),
                RealtimeSpatialEvent.EventType.valueOf(event.type().name()),
                event.anchorLocationEventId(),
                RealtimeSpatialEvent.StableState.valueOf(event.previousState().name()),
                RealtimeSpatialEvent.StableState.valueOf(event.currentState().name()),
                event.confirmationPoints(), fact.ingestSequence(), zone.runtimeRevision(),
                event.occurredAt(), evidence);
        String payloadJson = canonicalJson(payload);
        int outboxChanged = jdbc.update("""
                INSERT INTO spatial_event_outbox (
                    event_id, event_type, schema_version, payload, payload_hash,
                    headers, status, attempts, next_attempt_at, occurred_at, created_at
                ) VALUES (?, 'realtime.spatial.event.created', 1, CAST(? AS JSONB), ?,
                          CAST(? AS JSONB), 'PENDING', 0, CURRENT_TIMESTAMP, ?,
                          CURRENT_TIMESTAMP)
                """, eventId, payloadJson, hash(payloadJson), canonicalJson(Map.of(
                "partitionKey", request.siteId().toString(),
                "tagId", fact.tagId().toString(),
                "zoneId", event.zoneId().toString())), sqlTime(event.occurredAt()));
        if (outboxChanged != 1) {
            throw new IllegalStateException("空间重放事件未能原子创建 Outbox");
        }
        return 1;
    }

    private void markFailed(ReplayLease lease, Instant now, RuntimeException exception) {
        Duration delay = retryDelay(lease.attempts());
        boolean dead = lease.attempts() >= maxAttempts;
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                UPDATE spatial_replay_request
                SET status = ?, next_attempt_at = ?, lease_token = NULL,
                    locked_by = NULL, locked_at = NULL, lease_expires_at = NULL,
                    completed_at = ?, last_error = ?
                WHERE request_id = ? AND status = 'PROCESSING'
                  AND locked_by = ? AND lease_token = ?
                """, dead ? "DEAD" : "FAILED", sqlTime(now.plus(delay)),
                    dead ? sqlTime(now) : null,
                    (dead ? "REPLAY_DEAD:" : "REPLAY_BATCH_FAILED:")
                            + exception.getClass().getSimpleName(),
                    lease.requestId(), instanceId, lease.leaseToken());
            if (dead && changed == 1) {
                jdbc.update("DELETE FROM spatial_replay_subject_state WHERE request_id = ?",
                        lease.requestId());
            }
        });
    }

    private Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 30);
        long baseMillis = retryBaseDelay.toMillis();
        long candidate = baseMillis > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : baseMillis * multiplier;
        return Duration.ofMillis(Math.min(candidate, retryMaxDelay.toMillis()));
    }

    private ReplayRequest mapRequest(ResultSet rs, int rowNumber) throws SQLException {
        Number source = (Number) rs.getObject("source_watermark");
        Number cursorSequence = (Number) rs.getObject("cursor_ingest_sequence");
        return new ReplayRequest(
                rs.getObject("request_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getString("zone_type"),
                rs.getObject("zone_version_id", UUID.class),
                rs.getObject("window_start", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("window_end", java.time.OffsetDateTime.class).toInstant(),
                rs.getString("status"), rs.getInt("attempts"),
                rs.getObject("lease_token", UUID.class), rs.getString("locked_by"),
                instantOrNull(rs, "lease_expires_at"),
                source == null ? null : source.longValue(), instantOrNull(rs, "cursor_time"),
                cursorSequence == null ? null : cursorSequence.longValue(),
                rs.getString("source_snapshot"), rs.getString("zone_item_type"),
                rs.getString("zone_content"), rs.getString("zone_content_hash"),
                nullableLong(rs, "zone_runtime_revision"));
    }

    private ReplayFact mapFact(ResultSet rs, int rowNumber) throws SQLException {
        return new ReplayFact(
                rs.getObject("event_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getObject("tag_id", UUID.class),
                rs.getObject("vehicle_id", UUID.class),
                rs.getObject("person_id", UUID.class),
                rs.getDouble("local_x"), rs.getDouble("local_y"),
                nullableDouble(rs, "local_z"), rs.getString("quality_status"),
                rs.getString("quality_reason"),
                rs.getObject("time", java.time.OffsetDateTime.class).toInstant(),
                rs.getLong("ingest_sequence"));
    }

    private String canonicalJson(String json) {
        try {
            JsonNode tree = jsonMapper.readTree(json);
            if (tree == null || !tree.isObject()) {
                throw new IllegalStateException("空间重放区域快照必须是 JSON 对象");
            }
            Object value = jsonMapper.treeToValue(tree, Object.class);
            return jsonMapper.writeValueAsString(canonicalize(value));
        } catch (JacksonException exception) {
            throw new IllegalStateException("空间重放区域快照无法规范化", exception);
        }
    }

    private String canonicalJson(Object value) {
        try {
            Object tree = jsonMapper.treeToValue(jsonMapper.valueToTree(value), Object.class);
            return jsonMapper.writeValueAsString(canonicalize(tree));
        } catch (JacksonException exception) {
            throw new IllegalStateException("空间重放事件载荷无法规范化", exception);
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

    private Instant currentTime(Instant floor) {
        Instant current = clock.instant();
        return current.isAfter(floor) ? current : floor;
    }

    private static SpatialEventStateMachine.StableState nullableState(String value) {
        return value == null ? null : SpatialEventStateMachine.StableState.valueOf(value);
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.longValue();
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

    /** 单次重放批次执行结果。 */
    public record BatchResult(
            boolean processed,
            UUID requestId,
            int scannedPoints,
            int emittedEvents,
            boolean completed,
            boolean failed
    ) {
        private static BatchResult idle() {
            return new BatchResult(false, null, 0, 0, false, false);
        }
    }

    private record ReplayLease(UUID requestId, UUID leaseToken, int attempts) {
    }

    private record ReplayRequest(
            UUID requestId,
            UUID organizationId,
            UUID siteId,
            String zoneType,
            UUID zoneVersionId,
            Instant windowStart,
            Instant windowEnd,
            String status,
            int attempts,
            UUID leaseToken,
            String lockedBy,
            Instant leaseExpiresAt,
            Long sourceWatermark,
            Instant cursorTime,
            Long cursorIngestSequence,
            String sourceSnapshot,
            String zoneItemType,
            String zoneContent,
            String zoneContentHash,
            Long zoneRuntimeRevision
    ) {
    }

    private record ReplayFact(
            UUID eventId,
            UUID organizationId,
            UUID siteId,
            UUID tagId,
            UUID vehicleId,
            UUID personId,
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

    private record ZoneSource(
            String itemType,
            String contentJson,
            String contentHash,
            Long runtimeRevision
    ) {
    }

    private record ReplayZone(
            SpatialZoneCompiler.CompiledZone compiled,
            long runtimeRevision
    ) {
    }
}
