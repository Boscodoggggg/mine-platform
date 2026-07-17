package com.bosco.mine.telemetry.alarm.outbox;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 使用原子 SQL 精确认领并推进 Telemetry 告警 Outbox 事件。
 */
public class TelemetryOutboxRepository {

    private static final Set<String> EVENT_TYPES = Set.of("alarm.raised", "alarm.recovered");

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建告警 Outbox 仓储。
     *
     * @param dataSource Telemetry 时序数据库连接池
     */
    public TelemetryOutboxRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(
                dataSource, "告警 Outbox 数据源不能为空"));
    }

    /**
     * 只认领指定事件，其他实例持有有效租约时返回空。
     *
     * @param eventId 事件标识
     * @param instanceId 当前服务实例标识
     * @param leaseDuration 发布租约
     * @return 当前实例成功认领的事件
     */
    public Optional<ClaimedEvent> claimById(
            UUID eventId,
            String instanceId,
            Duration leaseDuration
    ) {
        Objects.requireNonNull(eventId, "告警 Outbox 事件标识不能为空");
        requireText(instanceId, "告警 Outbox 实例标识");
        requirePositive(leaseDuration, "告警 Outbox 租约");
        List<ClaimedEvent> claimed = jdbcTemplate.query("""
                UPDATE telemetry_outbox_event
                   SET status = 'PUBLISHING',
                       attempts = attempts + 1,
                       locked_by = ?,
                       locked_at = CURRENT_TIMESTAMP,
                       lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                       last_error = NULL
                 WHERE event_id = ?
                   AND aggregate_type = 'ALARM_OCCURRENCE'
                   AND event_type IN ('alarm.raised', 'alarm.recovered')
                   AND (
                       (status IN ('PENDING', 'FAILED')
                           AND next_attempt_at <= CURRENT_TIMESTAMP)
                       OR (status = 'PUBLISHING'
                           AND lease_expires_at <= CURRENT_TIMESTAMP)
                   )
                RETURNING event_id, aggregate_id, event_type, schema_version,
                          payload::TEXT, headers::TEXT, attempts, occurred_at
                """, this::mapEvent, instanceId, leaseDuration.toMillis(), eventId);
        return claimed.stream().findFirst();
    }

    /** 把当前实例持有的事件标记为 RabbitMQ 确认发布成功。 */
    public void markPublished(UUID eventId, String instanceId) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE telemetry_outbox_event
                   SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_at = NULL, lease_expires_at = NULL,
                       last_error = NULL
                 WHERE event_id = ? AND status = 'PUBLISHING' AND locked_by = ?
                """, eventId, instanceId), eventId, "发布成功");
    }

    /** 记录可重试失败、下次尝试时间并释放当前租约。 */
    public void markFailed(
            UUID eventId,
            String instanceId,
            Instant retryAt,
            String error
    ) {
        Objects.requireNonNull(retryAt, "告警 Outbox 重试时间不能为空");
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE telemetry_outbox_event
                   SET status = 'FAILED', next_attempt_at = ?, last_error = ?,
                       locked_by = NULL, locked_at = NULL, lease_expires_at = NULL
                 WHERE event_id = ? AND status = 'PUBLISHING' AND locked_by = ?
                """, retryAt.atOffset(java.time.ZoneOffset.UTC), truncate(error),
                eventId, instanceId), eventId, "发布失败");
    }

    /** 把超过尝试上限的事件标记为待人工处理的死信。 */
    public void markDead(UUID eventId, String instanceId, String error) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE telemetry_outbox_event
                   SET status = 'DEAD', last_error = ?,
                       locked_by = NULL, locked_at = NULL, lease_expires_at = NULL
                 WHERE event_id = ? AND status = 'PUBLISHING' AND locked_by = ?
                """, truncate(error), eventId, instanceId), eventId, "转入死信");
    }

    /**
     * 有界读取当前可认领或租约已过期的事件标识。
     *
     * @param limit 单次最大恢复数量
     * @return 按业务事实与事件标识排序的事件标识
     */
    public List<UUID> findRecoverableEventIds(int limit) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("告警 Outbox 恢复上限必须在 1 到 10000 之间");
        }
        return jdbcTemplate.queryForList("""
                SELECT event_id
                  FROM telemetry_outbox_event
                 WHERE aggregate_type = 'ALARM_OCCURRENCE'
                   AND event_type IN ('alarm.raised', 'alarm.recovered')
                   AND (
                       (status IN ('PENDING', 'FAILED')
                           AND next_attempt_at <= CURRENT_TIMESTAMP)
                       OR (status = 'PUBLISHING'
                           AND lease_expires_at <= CURRENT_TIMESTAMP)
                   )
                 ORDER BY occurred_at, event_id
                 LIMIT ?
                """, UUID.class, limit);
    }

    private ClaimedEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClaimedEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getInt("schema_version"),
                resultSet.getString("payload"),
                resultSet.getString("headers"),
                resultSet.getInt("attempts"),
                resultSet.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant()
        );
    }

    private void requireSingleUpdate(int updated, UUID eventId, String operation) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "告警 Outbox 事件状态已变化，无法" + operation + ": " + eventId);
        }
    }

    private String truncate(String error) {
        String value = error == null || error.isBlank() ? "未知发布错误" : error;
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private void requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(label + "长度不合法");
        }
    }

    private void requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
    }

    /**
     * 当前实例已认领、等待确认发布的不可变告警事件。
     *
     * @param eventId 消息总线 UUIDv7 事件标识
     * @param aggregateId 告警周期 UUIDv7 标识
     * @param eventType 告警触发或恢复事件类型
     * @param schemaVersion 载荷结构版本
     * @param payloadJson 完整业务载荷 JSON
     * @param headersJson 有界链路消息头 JSON
     * @param attempts 包含当前认领在内的尝试次数
     * @param occurredAt 告警业务事实时间
     */
    public record ClaimedEvent(
            UUID eventId,
            UUID aggregateId,
            String eventType,
            int schemaVersion,
            String payloadJson,
            String headersJson,
            int attempts,
            Instant occurredAt
    ) {
        /** 校验可靠发布所需字段完整且属于告警事件白名单。 */
        public ClaimedEvent {
            Objects.requireNonNull(eventId, "告警事件标识不能为空");
            Objects.requireNonNull(aggregateId, "告警周期标识不能为空");
            if (!EVENT_TYPES.contains(eventType)) {
                throw new IllegalArgumentException("告警 Outbox 事件类型不受支持");
            }
            if (schemaVersion != 1 || attempts < 1) {
                throw new IllegalArgumentException("告警事件版本必须为 1 且尝试次数必须大于 0");
            }
            if (payloadJson == null || payloadJson.isBlank()
                    || headersJson == null || headersJson.isBlank()) {
                throw new IllegalArgumentException("告警事件载荷和消息头不能为空");
            }
            Objects.requireNonNull(occurredAt, "告警业务事实时间不能为空");
        }
    }
}
