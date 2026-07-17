package com.bosco.mine.server.modules.alarm.repository;

import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 提供告警 Inbox、乱序聚合、查询和人工处置所需的数据库操作。
 */
@Repository
public class AlarmEventRepository {
    private static final String CONSUMER_NAME = "mine-server-alarm-event";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /**
     * 创建告警仓储。
     *
     * @param jdbcTemplate 数据库访问模板
     */
    public AlarmEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /** 登记告警消息 Inbox 幂等记录。 */
    public boolean recordInbox(UUID eventId) {
        return jdbcTemplate.update("""
                INSERT INTO sys_inbox_event (consumer_name, event_id, result)
                VALUES (?, ?, 'SUCCESS')
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, CONSUMER_NAME, eventId) == 1;
    }

    /** 对告警周期获取事务级排他锁，保护不存在记录的并发首写。 */
    public void lockOccurrence(UUID alarmId) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> { },
                "alarm:" + alarmId
        );
    }

    /** 查询设备当前所属产品编码，用于拒绝伪造或过期的身份。 */
    public Optional<String> findDeviceProductKey(UUID deviceId) {
        List<String> rows = jdbcTemplate.queryForList("""
                SELECT product.product_key
                FROM dev_device device
                JOIN dev_product product ON product.id = device.product_id
                WHERE device.id = ?
                """, String.class, deviceId);
        return rows.stream().findFirst();
    }

    /** 锁定并读取告警聚合。 */
    public Optional<AlarmEventRecord> lockById(UUID alarmId) {
        return queryOne("""
                SELECT alarm.*, device.device_code, device.name AS device_name
                FROM alarm_event alarm
                JOIN dev_device device ON device.id = alarm.device_id
                WHERE alarm.id = ?
                FOR UPDATE OF alarm
                """, alarmId);
    }

    /** 读取告警详情。 */
    public Optional<AlarmEventRecord> findById(UUID alarmId) {
        return queryOne("""
                SELECT alarm.*, device.device_code, device.name AS device_name
                FROM alarm_event alarm
                JOIN dev_device device ON device.id = alarm.device_id
                WHERE alarm.id = ?
                """, alarmId);
    }

    /** 首次以触发事实创建告警周期。 */
    public void insertRaised(UUID eventId, AlarmRaisedPayload payload, String evidenceJson, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO alarm_event (
                    id, device_id, product_key, rule_key, rule_name, source_type, severity,
                    property_id, policy_runtime_revision, policy_snapshot_id, policy_content_hash,
                    raised_at, raised_evidence, raised_event_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?)
                """,
                payload.alarmOccurrenceId(), payload.deviceId(), payload.productKey(),
                payload.ruleKey(), payload.ruleName(), payload.sourceType(), payload.severity(),
                payload.propertyId(), payload.policyRuntimeRevision(), payload.policySnapshotId(),
                payload.policyContentHash(), timestamp(payload.raisedAt()), evidenceJson, eventId,
                timestamp(now), timestamp(now)
        );
    }

    /** 首次以恢复事实创建告警周期，等待迟到的触发证据补齐。 */
    public void insertRecovered(
            UUID eventId,
            AlarmRecoveredPayload payload,
            String evidenceJson,
            Instant now
    ) {
        jdbcTemplate.update("""
                INSERT INTO alarm_event (
                    id, device_id, product_key, rule_key, rule_name, source_type, severity,
                    property_id, policy_runtime_revision, policy_snapshot_id, policy_content_hash,
                    raised_at, recovered_at, recovered_evidence, recovered_event_id,
                    recovery_reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?)
                """,
                payload.alarmOccurrenceId(), payload.deviceId(), payload.productKey(),
                payload.ruleKey(), payload.ruleName(), payload.sourceType(), payload.severity(),
                payload.propertyId(), payload.policyRuntimeRevision(), payload.policySnapshotId(),
                payload.policyContentHash(), timestamp(payload.raisedAt()), timestamp(payload.recoveredAt()),
                evidenceJson, eventId, payload.recoveryReason(), timestamp(now), timestamp(now)
        );
    }

    /** 补齐恢复先到场景中的触发事实。 */
    public void supplementRaised(UUID alarmId, UUID eventId, String evidenceJson, Instant now) {
        int changed = jdbcTemplate.update("""
                UPDATE alarm_event
                SET raised_evidence = CAST(? AS JSONB), raised_event_id = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND raised_event_id IS NULL
                """, evidenceJson, eventId, timestamp(now), alarmId);
        requireChanged(changed, "补齐告警触发事实");
    }

    /** 为已触发告警追加恢复事实。 */
    public void applyRecovery(
            UUID alarmId,
            UUID eventId,
            Instant recoveredAt,
            String evidenceJson,
            String recoveryReason,
            Instant now
    ) {
        int changed = jdbcTemplate.update("""
                UPDATE alarm_event
                SET recovered_at = ?, recovered_evidence = CAST(? AS JSONB), recovered_event_id = ?,
                    recovery_reason = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND recovered_event_id IS NULL
                """, timestamp(recoveredAt), evidenceJson, eventId, recoveryReason,
                timestamp(now), alarmId);
        requireChanged(changed, "追加告警恢复事实");
    }

    /** 按查询条件统计告警数量。 */
    public long count(AlarmQuery query) {
        SqlWithParameters filter = filter(query);
        return namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alarm_event alarm WHERE 1 = 1 " + filter.sql(),
                filter.parameters(), Long.class
        );
    }

    /** 按固定告警等级统计尚未恢复的活动告警数量。 */
    public long countActiveBySeverity(String severity) {
        if (!java.util.Set.of("INFO", "WARNING", "CRITICAL").contains(severity)) {
            throw new IllegalArgumentException("活动告警指标等级不受支持");
        }
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM alarm_event
                WHERE recovered_at IS NULL
                  AND closed_at IS NULL
                  AND severity = ?
                """, Long.class, severity);
        return count == null ? 0L : count;
    }

    /** 按时间倒序分页查询告警。 */
    public List<AlarmEventRecord> find(AlarmQuery query, int page, int size) {
        SqlWithParameters filter = filter(query);
        filter.parameters().addValue("limit", size)
                .addValue("offset", (long) (page - 1) * size);
        return namedJdbcTemplate.query("""
                SELECT alarm.*, device.device_code, device.name AS device_name
                FROM alarm_event alarm
                JOIN dev_device device ON device.id = alarm.device_id
                WHERE 1 = 1
                """ + filter.sql() + " ORDER BY alarm.raised_at DESC, alarm.id DESC LIMIT :limit OFFSET :offset",
                filter.parameters(), this::mapRecord);
    }

    /** 按告警和幂等键读取既有确认事实。 */
    public Optional<AcknowledgementRecord> findAcknowledgement(UUID alarmId, String idempotencyKey) {
        List<AcknowledgementRecord> rows = jdbcTemplate.query("""
                SELECT id, operator_id, note, idempotency_key, acknowledged_at
                FROM alarm_acknowledgement
                WHERE alarm_id = ? AND idempotency_key = ?
                """, (rs, rowNum) -> new AcknowledgementRecord(
                rs.getObject("id", UUID.class), rs.getObject("operator_id", UUID.class),
                rs.getString("note"), rs.getString("idempotency_key"),
                instant(rs.getTimestamp("acknowledged_at"))
        ), alarmId, idempotencyKey);
        return rows.stream().findFirst();
    }

    /** 保存唯一确认事实。 */
    public void insertAcknowledgement(
            UUID id,
            UUID alarmId,
            UUID operatorId,
            String note,
            String idempotencyKey,
            Instant acknowledgedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO alarm_acknowledgement (
                    id, alarm_id, operator_id, note, idempotency_key, acknowledged_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, id, alarmId, operatorId, note, idempotencyKey, timestamp(acknowledgedAt));
    }

    /** 以版本条件写入首次确认事实。 */
    public int acknowledge(
            UUID alarmId,
            UUID operatorId,
            String note,
            Instant acknowledgedAt,
            long expectedVersion
    ) {
        return jdbcTemplate.update("""
                UPDATE alarm_event
                SET acknowledged_at = ?, acknowledged_by = ?, acknowledgement_note = ?,
                    updated_at = ?, version = version + 1
                WHERE id = ? AND version = ? AND acknowledged_at IS NULL
                """, timestamp(acknowledgedAt), operatorId, note, timestamp(acknowledgedAt),
                alarmId, expectedVersion);
    }

    /** 以版本条件关闭一个已经恢复的告警。 */
    public int close(
            UUID alarmId,
            UUID operatorId,
            String note,
            String idempotencyKey,
            Instant closedAt,
            long expectedVersion
    ) {
        return jdbcTemplate.update("""
                UPDATE alarm_event
                SET closed_at = ?, closed_by = ?, close_note = ?, close_idempotency_key = ?,
                    updated_at = ?, version = version + 1
                WHERE id = ? AND version = ? AND recovered_at IS NOT NULL AND closed_at IS NULL
                """, timestamp(closedAt), operatorId, note, idempotencyKey, timestamp(closedAt),
                alarmId, expectedVersion);
    }

    private Optional<AlarmEventRecord> queryOne(String sql, UUID alarmId) {
        List<AlarmEventRecord> rows = jdbcTemplate.query(sql, this::mapRecord, alarmId);
        return rows.stream().findFirst();
    }

    private AlarmEventRecord mapRecord(ResultSet rs, int rowNumber) throws SQLException {
        return new AlarmEventRecord(
                rs.getObject("id", UUID.class), rs.getObject("device_id", UUID.class),
                rs.getString("device_code"), rs.getString("device_name"),
                rs.getString("product_key"), rs.getString("rule_key"), rs.getString("rule_name"),
                rs.getString("source_type"), rs.getString("severity"), rs.getString("property_id"),
                rs.getLong("policy_runtime_revision"), rs.getObject("policy_snapshot_id", UUID.class),
                rs.getString("policy_content_hash"), instant(rs.getTimestamp("raised_at")),
                rs.getString("raised_evidence"), rs.getObject("raised_event_id", UUID.class),
                instant(rs.getTimestamp("recovered_at")), rs.getString("recovered_evidence"),
                rs.getObject("recovered_event_id", UUID.class), rs.getString("recovery_reason"),
                instant(rs.getTimestamp("acknowledged_at")), rs.getObject("acknowledged_by", UUID.class),
                rs.getString("acknowledgement_note"), instant(rs.getTimestamp("closed_at")),
                rs.getObject("closed_by", UUID.class), rs.getString("close_note"),
                rs.getString("close_idempotency_key"), rs.getLong("version")
        );
    }

    private SqlWithParameters filter(AlarmQuery query) {
        StringBuilder sql = new StringBuilder();
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (query.active() != null) {
            sql.append(query.active()
                    ? " AND alarm.recovered_at IS NULL AND alarm.closed_at IS NULL"
                    : " AND alarm.recovered_at IS NOT NULL");
        }
        if (query.status() != null) {
            sql.append(switch (query.status()) {
                case ACTIVE_UNACKNOWLEDGED ->
                        " AND alarm.closed_at IS NULL AND alarm.recovered_at IS NULL"
                                + " AND alarm.acknowledged_at IS NULL";
                case ACTIVE_ACKNOWLEDGED ->
                        " AND alarm.closed_at IS NULL AND alarm.recovered_at IS NULL"
                                + " AND alarm.acknowledged_at IS NOT NULL";
                case RECOVERED_UNACKNOWLEDGED ->
                        " AND alarm.closed_at IS NULL AND alarm.recovered_at IS NOT NULL"
                                + " AND alarm.acknowledged_at IS NULL";
                case RECOVERED_ACKNOWLEDGED ->
                        " AND alarm.closed_at IS NULL AND alarm.recovered_at IS NOT NULL"
                                + " AND alarm.acknowledged_at IS NOT NULL";
                case CLOSED -> " AND alarm.closed_at IS NOT NULL";
            });
        }
        if (query.severity() != null) {
            sql.append(" AND alarm.severity = :severity");
            parameters.addValue("severity", query.severity());
        }
        if (query.deviceId() != null) {
            sql.append(" AND alarm.device_id = :deviceId");
            parameters.addValue("deviceId", query.deviceId());
        }
        if (query.ruleKey() != null) {
            sql.append(" AND alarm.rule_key = :ruleKey");
            parameters.addValue("ruleKey", query.ruleKey());
        }
        if (query.from() != null) {
            sql.append(" AND alarm.raised_at >= :from");
            parameters.addValue("from", timestamp(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND alarm.raised_at < :to");
            parameters.addValue("to", timestamp(query.to()));
        }
        return new SqlWithParameters(sql.toString(), parameters);
    }

    private static void requireChanged(int changed, String action) {
        if (changed != 1) {
            throw new IllegalStateException(action + "时告警并发状态已变化");
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** 告警查询条件。 */
    public record AlarmQuery(
            Boolean active,
            AlarmStatus status,
            String severity,
            UUID deviceId,
            String ruleKey,
            Instant from,
            Instant to
    ) {
    }

    /** 告警数据库快照。 */
    public record AlarmEventRecord(
            UUID id, UUID deviceId, String deviceCode, String deviceName,
            String productKey, String ruleKey, String ruleName, String sourceType,
            String severity, String propertyId, long policyRuntimeRevision,
            UUID policySnapshotId, String policyContentHash, Instant raisedAt,
            String raisedEvidenceJson, UUID raisedEventId, Instant recoveredAt,
            String recoveredEvidenceJson, UUID recoveredEventId, String recoveryReason,
            Instant acknowledgedAt, UUID acknowledgedBy, String acknowledgementNote,
            Instant closedAt, UUID closedBy, String closeNote, String closeIdempotencyKey,
            long version
    ) {
    }

    /** 告警确认事实。 */
    public record AcknowledgementRecord(
            UUID id, UUID operatorId, String note, String idempotencyKey, Instant acknowledgedAt
    ) {
    }

    private record SqlWithParameters(String sql, MapSqlParameterSource parameters) {
    }
}
