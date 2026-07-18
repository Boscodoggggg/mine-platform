package com.bosco.mine.telemetry.location;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.bosco.mine.model.location.LocationObservation;
import com.bosco.mine.telemetry.location.snapshot.LocationRuntimeRegistry;
import com.bosco.mine.telemetry.location.transform.CoordinateTransformRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在单事务内完成定位幂等、历史事实、隔离证据和可靠实时 Outbox 写入。
 */
public class LocationFactWriter {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final LocationRuntimeResolver resolver;
    private final IdGenerator idGenerator;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建使用最后正确快照和 UUIDv7 隔离标识的位置事实写入器。
     *
     * @param dataSource Telemetry 时序数据库
     * @param registry 最后正确定位快照注册表
     * @param transformRegistry 坐标转换算法注册表
     * @param qualityEvaluator 定位质量判定器
     */
    public LocationFactWriter(
            DataSource dataSource,
            LocationRuntimeRegistry registry,
            CoordinateTransformRegistry transformRegistry,
            LocationQualityEvaluator qualityEvaluator
    ) {
        Objects.requireNonNull(dataSource, "定位事实数据源不能为空");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.resolver = new LocationRuntimeResolver(
                registry, transformRegistry, qualityEvaluator);
        this.idGenerator = IdGenerator.timeOrdered();
    }

    /**
     * 处理一条已通过强类型契约校验的定位观测。
     *
     * @param observation 可信来源设备定位观测
     * @return 已存储、重复或已隔离结果
     */
    public WriteResult write(LocationObservation observation) {
        Objects.requireNonNull(observation, "定位观测不能为空");
        String payloadJson = canonicalJson(observation);
        String payloadHash = hash(payloadJson);
        Existing existing = findExisting(
                observation.observationId(), observation.sourceDeviceId(),
                observation.bootId(), observation.sequence());
        if (existing != null) {
            return existingResult(
                    existing, observation.observationId(), observation.sourceDeviceId(),
                    observation.bootId(), observation.sequence(), payloadHash);
        }

        LocationRuntimeResolver.ResolvedFact fact;
        try {
            fact = resolver.resolve(observation);
        } catch (LocationRuntimeResolver.QuarantineDecision decision) {
            return storeQuarantine(observation, payloadHash, decision);
        }
        WriteResult result = transactionTemplate.execute(status ->
                storeFact(observation, payloadHash, fact));
        return Objects.requireNonNull(result, "定位事实事务不得返回空结果");
    }

    /**
     * 为消息头可信但强类型载荷非法的观测保存有界诊断证据。
     *
     * @param candidate 可安全提取幂等身份和时间的非法观测
     * @return 已隔离处理结果
     */
    public WriteResult quarantineInvalid(InvalidObservationCandidate candidate) {
        Objects.requireNonNull(candidate, "非法定位观测候选不能为空");
        String payloadHash = hash(candidate.rawBody());
        Existing existing = findExisting(
                candidate.observationId(), candidate.sourceDeviceId(),
                candidate.bootId(), candidate.sequence());
        if (existing != null) {
            return existingResult(
                    existing, candidate.observationId(), candidate.sourceDeviceId(),
                    candidate.bootId(), candidate.sequence(), payloadHash);
        }
        WriteResult result = transactionTemplate.execute(status -> {
            int inserted = insertInbox(
                    candidate.observationId(), candidate.sourceDeviceId(),
                    candidate.bootId(), candidate.sequence(), payloadHash,
                    candidate.occurredAt(), candidate.receivedAt(), "QUARANTINED", null);
            if (inserted == 0) {
                Existing concurrent = findExisting(
                        candidate.observationId(), candidate.sourceDeviceId(),
                        candidate.bootId(), candidate.sequence());
                if (concurrent == null) {
                    throw new IllegalStateException("非法定位隔离冲突后无法读取既有结果");
                }
                return existingResult(
                        concurrent, candidate.observationId(), candidate.sourceDeviceId(),
                        candidate.bootId(), candidate.sequence(), payloadHash);
            }
            insertQuarantine(
                    candidate.observationId(), candidate.sourceDeviceId(),
                    null, null, QuarantineReason.INVALID_OBSERVATION,
                    payloadHash, Map.of("format", "INVALID_OBSERVATION"),
                    candidate.occurredAt(), candidate.receivedAt());
            return new WriteResult(Outcome.QUARANTINED, null);
        });
        return Objects.requireNonNull(result, "非法定位隔离事务不得返回空结果");
    }

    private WriteResult storeFact(
            LocationObservation observation,
            String payloadHash,
            LocationRuntimeResolver.ResolvedFact fact
    ) {
        long ingestSequence = Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT nextval('location_ingest_sequence_seq')", Long.class));
        int insertedInbox = insertInbox(
                observation.observationId(), observation.sourceDeviceId(),
                observation.bootId(), observation.sequence(), payloadHash,
                observation.occurredAt(), observation.receivedAt(),
                "STORED", ingestSequence);
        if (insertedInbox == 0) {
            Existing concurrent = findExisting(
                    observation.observationId(), observation.sourceDeviceId(),
                    observation.bootId(), observation.sequence());
            if (concurrent == null) {
                throw new IllegalStateException("定位幂等冲突后无法读取既有结果");
            }
            return existingResult(
                    concurrent, observation.observationId(), observation.sourceDeviceId(),
                    observation.bootId(), observation.sequence(), payloadHash);
        }

        jdbcTemplate.update("""
                INSERT INTO location_point (
                    time, organization_id, site_id, source_device_id, tag_id,
                    source_coordinate_system_id, source_x, source_y, source_z,
                    target_coordinate_system_id, local_x, local_y, local_z,
                    transform_version_id, binding_type, binding_id, vehicle_id, person_id,
                    speed_meters_per_second, heading_degrees, accuracy_meters,
                    quality_status, quality_reason, boot_id, sequence, event_id,
                    ingest_sequence, received_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                Timestamp.from(observation.occurredAt()),
                fact.organizationId(), fact.siteId(), observation.sourceDeviceId(), fact.tagId(),
                fact.sourceCoordinateSystemId(), observation.x(), observation.y(), observation.z(),
                fact.targetCoordinateSystemId(), fact.coordinate().x(), fact.coordinate().y(),
                fact.coordinate().z(), fact.coordinate().transformVersionId(),
                fact.bindingType(), fact.bindingId(), fact.vehicleId(), fact.personId(),
                observation.speedMetersPerSecond(), observation.headingDegrees(),
                observation.accuracyMeters(), fact.quality().status().name(),
                fact.quality().reason(), observation.bootId(), observation.sequence(),
                observation.observationId(), ingestSequence,
                Timestamp.from(observation.receivedAt()));

        RealtimeLocationEvent event = new RealtimeLocationEvent(
                observation.observationId(), fact.organizationId(), fact.siteId(),
                observation.sourceDeviceId(), fact.tagId(), fact.tagCode(),
                fact.targetCoordinateSystemId(), fact.vehicleId(), fact.personId(),
                ingestSequence, observation.occurredAt(), observation.receivedAt(),
                fact.coordinate().x(), fact.coordinate().y(), fact.coordinate().z(),
                observation.speedMetersPerSecond(), observation.headingDegrees(),
                observation.accuracyMeters(), fact.quality().status(), fact.quality().reason());
        String eventJson = canonicalJson(event);
        jdbcTemplate.update("""
                INSERT INTO location_realtime_outbox (
                    event_id, ingest_sequence, event_type, schema_version,
                    payload, payload_hash, headers, status, attempts,
                    next_attempt_at, occurred_at, created_at
                ) VALUES (
                    ?, ?, 'realtime.location.updated', 1,
                    CAST(? AS JSONB), ?, CAST(? AS JSONB), 'PENDING', 0,
                    CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP
                )
                """, event.eventId(), ingestSequence, eventJson, hash(eventJson),
                canonicalJson(Map.of(
                        "partitionKey", fact.siteId().toString(),
                        "tagId", fact.tagId().toString())),
                Timestamp.from(event.occurredAt()));
        return new WriteResult(Outcome.STORED, event);
    }

    private WriteResult storeQuarantine(
            LocationObservation observation,
            String payloadHash,
            LocationRuntimeResolver.QuarantineDecision decision
    ) {
        WriteResult result = transactionTemplate.execute(status -> {
            int insertedInbox = insertInbox(
                    observation.observationId(), observation.sourceDeviceId(),
                    observation.bootId(), observation.sequence(), payloadHash,
                    observation.occurredAt(), observation.receivedAt(),
                    "QUARANTINED", null);
            if (insertedInbox == 0) {
                Existing concurrent = findExisting(
                        observation.observationId(), observation.sourceDeviceId(),
                        observation.bootId(), observation.sequence());
                if (concurrent == null) {
                    throw new IllegalStateException("定位隔离幂等冲突后无法读取既有结果");
                }
                return existingResult(
                        concurrent, observation.observationId(), observation.sourceDeviceId(),
                        observation.bootId(), observation.sequence(), payloadHash);
            }
            insertQuarantine(
                    observation.observationId(), observation.sourceDeviceId(),
                    decision.organizationId(), decision.siteId(), decision.reason(),
                    payloadHash, Map.of(
                            "tagCode", observation.tagCode(),
                            "coordinateSystemCode", observation.coordinateSystemCode()),
                    observation.occurredAt(), observation.receivedAt());
            return new WriteResult(Outcome.QUARANTINED, null);
        });
        return Objects.requireNonNull(result, "定位隔离事务不得返回空结果");
    }

    private int insertInbox(
            UUID eventId,
            UUID sourceDeviceId,
            String bootId,
            long sourceSequence,
            String payloadHash,
            Instant occurredAt,
            Instant receivedAt,
            String result,
            Long ingestSequence
    ) {
        return jdbcTemplate.update("""
                INSERT INTO location_inbox (
                    event_id, source_device_id, boot_id, source_sequence,
                    payload_hash, occurred_at, received_at, result,
                    ingest_sequence, processed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
                """, eventId, sourceDeviceId, bootId, sourceSequence, payloadHash,
                Timestamp.from(occurredAt), Timestamp.from(receivedAt),
                result, ingestSequence);
    }

    private void insertQuarantine(
            UUID eventId,
            UUID sourceDeviceId,
            UUID organizationId,
            UUID siteId,
            QuarantineReason reason,
            String payloadHash,
            Map<String, Object> evidence,
            Instant occurredAt,
            Instant receivedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO location_quarantine (
                    quarantine_id, event_id, organization_id, site_id,
                    source_device_id, reason_code, payload_hash, evidence,
                    occurred_at, received_at, created_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '7 days'
                )
                """, idGenerator.next(), eventId, organizationId, siteId,
                sourceDeviceId, reason.name(), payloadHash, canonicalJson(evidence),
                Timestamp.from(occurredAt), Timestamp.from(receivedAt));
    }

    private Existing findExisting(
            UUID eventId,
            UUID sourceDeviceId,
            String bootId,
            long sourceSequence
    ) {
        List<Existing> rows = jdbcTemplate.query("""
                SELECT inbox.event_id, inbox.source_device_id, inbox.boot_id,
                       inbox.source_sequence, inbox.payload_hash, inbox.result,
                       outbox.payload::TEXT
                FROM location_inbox inbox
                LEFT JOIN location_realtime_outbox outbox ON outbox.event_id = inbox.event_id
                WHERE inbox.event_id = ?
                   OR (inbox.source_device_id = ? AND inbox.boot_id = ?
                       AND inbox.source_sequence = ?)
                ORDER BY inbox.event_id
                """, (resultSet, rowNumber) -> new Existing(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("source_device_id", UUID.class),
                resultSet.getString("boot_id"), resultSet.getLong("source_sequence"),
                resultSet.getString("payload_hash"), resultSet.getString("result"),
                resultSet.getString("payload")),
                eventId, sourceDeviceId, bootId, sourceSequence);
        if (rows.size() > 1) {
            throw new PermanentLocationObservationException(
                    "定位事件标识与来源顺序分别命中了不同既有记录");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private WriteResult existingResult(
            Existing existing,
            UUID eventId,
            UUID sourceDeviceId,
            String bootId,
            long sourceSequence,
            String payloadHash
    ) {
        if (!existing.eventId().equals(eventId)
                || !existing.sourceDeviceId().equals(sourceDeviceId)
                || !existing.bootId().equals(bootId)
                || existing.sourceSequence() != sourceSequence
                || !existing.payloadHash().equals(payloadHash)) {
            throw new PermanentLocationObservationException(
                    "重复定位标识或来源顺序对应了不同内容");
        }
        if ("QUARANTINED".equals(existing.result())) {
            return new WriteResult(Outcome.QUARANTINED, null);
        }
        if (!"STORED".equals(existing.result()) || existing.outboxPayload() == null) {
            throw new IllegalStateException("既有定位处理结果不完整");
        }
        try {
            return new WriteResult(
                    Outcome.DUPLICATE,
                    jsonMapper.readValue(
                            existing.outboxPayload(), RealtimeLocationEvent.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("既有定位实时事件无法恢复", exception);
        }
    }

    private String canonicalJson(Object value) {
        try {
            Object tree = jsonMapper.treeToValue(jsonMapper.valueToTree(value), Object.class);
            return jsonMapper.writeValueAsString(canonicalize(tree));
        } catch (JacksonException exception) {
            throw new IllegalStateException("定位事实无法规范化", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new java.util.TreeMap<>();
            map.forEach((key, item) -> result.put(
                    String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private String hash(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    /** 定位观测的持久处理结果。 */
    public enum Outcome {
        /** 新事实与实时 Outbox 已提交。 */
        STORED,
        /** 同一内容已经处理。 */
        DUPLICATE,
        /** 永久运行语义错误已写入有界隔离区。 */
        QUARANTINED
    }

    /** 数据库隔离区允许的固定低基数原因。 */
    public enum QuarantineReason {
        UNKNOWN_TAG,
        ARCHIVED_TAG,
        SOURCE_DEVICE_UNAUTHORIZED,
        NON_FINITE_COORDINATE,
        OUT_OF_BOUNDS,
        TRANSFORM_MISSING,
        TRANSFORM_VERSION_CONFLICT,
        SNAPSHOT_UNAVAILABLE,
        SNAPSHOT_INVALID,
        INVALID_OBSERVATION
    }

    /**
     * 已提交处理结果；只有已存储或重复事实携带可恢复的实时事件。
     *
     * @param outcome 固定处理结果
     * @param event 已提交实时事件，隔离时为空
     */
    public record WriteResult(Outcome outcome, RealtimeLocationEvent event) {
        /** 校验处理结果与实时事件的一致性。 */
        public WriteResult {
            Objects.requireNonNull(outcome, "定位写入结果不能为空");
            if ((outcome == Outcome.QUARANTINED) != (event == null)) {
                throw new IllegalArgumentException("定位写入结果与实时事件不一致");
            }
        }
    }

    /**
     * 从非法载荷中安全提取的幂等身份和时间，不保留原始正文到数据库。
     *
     * @param observationId 观测 UUIDv7 标识
     * @param sourceDeviceId 可信来源设备标识
     * @param bootId 设备启动周期
     * @param sequence 启动周期内正序号
     * @param occurredAt 设备发生时间
     * @param receivedAt Gateway 接收时间
     * @param rawBody 仅用于计算摘要的原始消息字节
     */
    public record InvalidObservationCandidate(
            UUID observationId,
            UUID sourceDeviceId,
            String bootId,
            long sequence,
            Instant occurredAt,
            Instant receivedAt,
            byte[] rawBody
    ) {
        /** 校验隔离表和 Inbox 所需的最小安全身份。 */
        public InvalidObservationCandidate {
            Objects.requireNonNull(observationId, "非法观测标识不能为空");
            if (observationId.version() != 7) {
                throw new IllegalArgumentException("非法观测标识必须是 UUIDv7");
            }
            Objects.requireNonNull(sourceDeviceId, "非法观测来源设备不能为空");
            if (bootId == null || bootId.isBlank() || bootId.length() > 128 || sequence < 1L) {
                throw new IllegalArgumentException("非法观测来源顺序不合法");
            }
            Objects.requireNonNull(occurredAt, "非法观测发生时间不能为空");
            Objects.requireNonNull(receivedAt, "非法观测接收时间不能为空");
            if (rawBody == null || rawBody.length == 0 || rawBody.length > 1_048_576) {
                throw new IllegalArgumentException("非法观测正文大小不合法");
            }
            rawBody = rawBody.clone();
        }

        /** @return 与内部状态隔离的原始消息副本 */
        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }
    }

    /** 重投无法修复且不能覆盖既有证据的幂等冲突。 */
    public static final class PermanentLocationObservationException extends RuntimeException {
        /** @param message 不包含原始载荷的固定错误摘要 */
        public PermanentLocationObservationException(String message) {
            super(message);
        }
    }

    private record Existing(
            UUID eventId,
            UUID sourceDeviceId,
            String bootId,
            long sourceSequence,
            String payloadHash,
            String result,
            String outboxPayload
    ) {
    }
}
