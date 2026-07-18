package com.bosco.mine.server.modules.command.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用原子 SQL 对 Outbox 事件进行精确认领和状态推进。
 */
@Repository
public class OutboxRelayRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 Outbox 发布仓储。
     *
     * @param jdbcTemplate 数据库访问模板
     */
    public OutboxRelayRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 只认领指定事件；其他服务实例持有有效租约时返回空。
     *
     * @param eventId 事件标识
     * @param instanceId 当前实例标识
     * @param leaseDuration 认领租约
     * @return 已认领事件
     */
    public Optional<OutboxEvent> claimById(
            UUID eventId,
            String instanceId,
            Duration leaseDuration
    ) {
        List<OutboxEvent> claimed = jdbcTemplate.query("""
                UPDATE sys_outbox_event
                SET status = 'PUBLISHING',
                    attempts = attempts + 1,
                    locked_by = ?,
                    locked_at = CURRENT_TIMESTAMP,
                    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    last_error = NULL
                WHERE id = ?
                  AND (
                    (aggregate_type = 'COMMAND' AND event_type = 'command.dispatch.requested')
                    OR (aggregate_type = 'CONFIG_RELEASE' AND event_type = 'config.release.created')
                    OR (aggregate_type = 'STRATEGY_RUNTIME_SNAPSHOT'
                        AND event_type = 'strategy.runtime.changed')
                    OR (aggregate_type = 'LOCATION_RUNTIME_SNAPSHOT'
                        AND event_type = 'location.runtime.changed')
                  )
                  AND (
                    (status IN ('PENDING', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP)
                    OR (status = 'PUBLISHING' AND lease_expires_at <= CURRENT_TIMESTAMP)
                  )
                RETURNING id, aggregate_id, event_type, schema_version,
                          payload::TEXT, headers::TEXT, attempts, created_at
                """, this::mapEvent, instanceId, leaseDuration.toMillis(), eventId);
        return claimed.stream().findFirst();
    }

    /**
     * 把当前实例持有的事件标记为发布成功。
     *
     * @param eventId 事件标识
     * @param instanceId 当前实例标识
     */
    public void markPublished(UUID eventId, String instanceId) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE sys_outbox_event
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                    locked_by = NULL, locked_at = NULL, lease_expires_at = NULL,
                    last_error = NULL
                WHERE id = ? AND status = 'PUBLISHING' AND locked_by = ?
                """, eventId, instanceId), eventId, "发布成功");
    }

    /**
     * 记录可重试失败并释放租约。
     *
     * @param eventId 事件标识
     * @param instanceId 当前实例标识
     * @param retryAt 下次尝试时间
     * @param error 错误摘要
     */
    public void markFailed(UUID eventId, String instanceId, Instant retryAt, String error) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE sys_outbox_event
                SET status = 'FAILED', next_attempt_at = ?, last_error = ?,
                    locked_by = NULL, locked_at = NULL, lease_expires_at = NULL
                WHERE id = ? AND status = 'PUBLISHING' AND locked_by = ?
                """, retryAt, truncate(error), eventId, instanceId), eventId, "发布失败");
    }

    /**
     * 把超过尝试上限的事件标记为待人工处理。
     *
     * @param eventId 事件标识
     * @param instanceId 当前实例标识
     * @param error 错误摘要
     */
    public void markDead(UUID eventId, String instanceId, String error) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE sys_outbox_event
                SET status = 'DEAD', last_error = ?,
                    locked_by = NULL, locked_at = NULL, lease_expires_at = NULL
                WHERE id = ? AND status = 'PUBLISHING' AND locked_by = ?
                """, truncate(error), eventId, instanceId), eventId, "转入死信");
    }

    /**
     * 仅在启动或连接恢复时有界读取可恢复事件标识。
     *
     * @param limit 单次最大恢复数量
     * @return 按创建时间排序的事件标识
     */
    public List<UUID> findRecoverableEventIds(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("恢复上限必须大于 0");
        }
        return jdbcTemplate.queryForList("""
                SELECT id
                FROM sys_outbox_event
                WHERE (
                    (aggregate_type = 'COMMAND' AND event_type = 'command.dispatch.requested')
                    OR (aggregate_type = 'CONFIG_RELEASE' AND event_type = 'config.release.created')
                    OR (aggregate_type = 'STRATEGY_RUNTIME_SNAPSHOT'
                        AND event_type = 'strategy.runtime.changed')
                    OR (aggregate_type = 'LOCATION_RUNTIME_SNAPSHOT'
                        AND event_type = 'location.runtime.changed')
                  )
                  AND (
                    (status IN ('PENDING', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP)
                    OR (status = 'PUBLISHING' AND lease_expires_at <= CURRENT_TIMESTAMP)
                  )
                ORDER BY created_at, id
                LIMIT ?
                """, UUID.class, limit);
    }

    private OutboxEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OutboxEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getInt("schema_version"),
                resultSet.getString("payload"),
                resultSet.getString("headers"),
                resultSet.getInt("attempts"),
                resultSet.getObject("created_at", java.time.OffsetDateTime.class).toInstant()
        );
    }

    private void requireSingleUpdate(int updated, UUID eventId, String operation) {
        if (updated != 1) {
            throw new IllegalStateException("Outbox 事件状态已变化，无法" + operation + ": " + eventId);
        }
    }

    private String truncate(String error) {
        String value = error == null || error.isBlank() ? "未知发布错误" : error;
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
