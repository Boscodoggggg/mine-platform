package com.bosco.mine.server.modules.mine.operation.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用原子 SQL 领取和推进专用运营 Outbox 状态。
 */
@Repository
public class OperationOutboxRepository implements OperationOutboxRelay.Repository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建运营 Outbox 仓储。
     *
     * @param jdbcTemplate 数据库访问模板
     */
    public OperationOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "数据库访问模板不能为空");
    }

    /** 使用随机租约精确领取到期事件。 */
    @Override
    public Optional<OperationOutboxRelay.ClaimedEvent> claimById(
            UUID eventId,
            UUID leaseToken,
            Duration leaseDuration
    ) {
        List<OperationOutboxRelay.ClaimedEvent> events = jdbcTemplate.query("""
                UPDATE mine_operation_outbox
                SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                    lease_token = ?,
                    leased_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    last_error = NULL
                WHERE id = ?
                  AND event_type = 'realtime.operation.updated'
                  AND event_version = 1
                  AND attempt_count < 100
                  AND (
                      (status IN ('PENDING', 'FAILED')
                          AND next_attempt_at <= CURRENT_TIMESTAMP)
                      OR (status = 'PROCESSING' AND leased_until <= CURRENT_TIMESTAMP)
                  )
                RETURNING id, organization_id, site_id,
                          aggregate_type, aggregate_id,
                          event_type, event_version, business_sequence,
                          payload::TEXT AS payload_json,
                          attempt_count, created_at
                """, this::mapEvent, leaseToken, leaseDuration.toMillis(), eventId);
        return events.stream().findFirst();
    }

    /** 使用持有租约标记发布成功。 */
    @Override
    public void markPublished(UUID eventId, UUID leaseToken) {
        requireSingle(jdbcTemplate.update("""
                UPDATE mine_operation_outbox
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                    lease_token = NULL, leased_until = NULL, last_error = NULL
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, eventId, leaseToken), eventId, "标记发布成功");
    }

    /** 使用持有租约记录可重试失败。 */
    @Override
    public void markFailed(
            UUID eventId,
            UUID leaseToken,
            Instant retryAt,
            String error
    ) {
        requireSingle(jdbcTemplate.update("""
                UPDATE mine_operation_outbox
                SET status = 'FAILED', next_attempt_at = ?, last_error = ?,
                    lease_token = NULL, leased_until = NULL
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, OffsetDateTime.ofInstant(retryAt, ZoneOffset.UTC),
                error, eventId, leaseToken), eventId, "记录发布失败");
    }

    /** 使用持有租约将事件转入死信。 */
    @Override
    public void markDead(UUID eventId, UUID leaseToken, String error) {
        requireSingle(jdbcTemplate.update("""
                UPDATE mine_operation_outbox
                SET status = 'DEAD', last_error = ?,
                    lease_token = NULL, leased_until = NULL
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, error, eventId, leaseToken), eventId, "转入死信");
    }

    /** 有界返回当前可恢复事件标识。 */
    @Override
    public List<UUID> findRecoverableEventIds(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("运营 Outbox 恢复上限必须大于 0");
        }
        return jdbcTemplate.queryForList("""
                SELECT id
                FROM mine_operation_outbox
                WHERE event_type = 'realtime.operation.updated'
                  AND event_version = 1
                  AND attempt_count < 100
                  AND (
                      (status IN ('PENDING', 'FAILED')
                          AND next_attempt_at <= CURRENT_TIMESTAMP)
                      OR (status = 'PROCESSING' AND leased_until <= CURRENT_TIMESTAMP)
                  )
                ORDER BY site_id, business_sequence
                LIMIT ?
                """, UUID.class, limit);
    }

    private OperationOutboxRelay.ClaimedEvent mapEvent(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new OperationOutboxRelay.ClaimedEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("site_id", UUID.class),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getInt("event_version"),
                resultSet.getLong("business_sequence"),
                resultSet.getString("payload_json"),
                resultSet.getInt("attempt_count"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private void requireSingle(int changed, UUID eventId, String action) {
        if (changed != 1) {
            throw new IllegalStateException(
                    "运营 Outbox 状态已变化，无法" + action + ": " + eventId);
        }
    }
}
