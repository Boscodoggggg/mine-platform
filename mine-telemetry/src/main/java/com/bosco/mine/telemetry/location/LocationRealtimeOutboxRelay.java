package com.bosco.mine.telemetry.location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 以数据库租约认领位置实时 Outbox，并通过发布确认、退避和有界扫描可靠转发。
 */
public final class LocationRealtimeOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LocationRealtimeOutboxRelay.class);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|token|secret|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final JdbcTemplate jdbcTemplate;
    private final Publisher publisher;
    private final RetryScheduler retryScheduler;
    private final String instanceId;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final int recoveryLimit;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final Clock clock;

    /** 创建带有界租约、退避和扫描参数的位置实时 Outbox 中继器。 */
    public LocationRealtimeOutboxRelay(
            DataSource dataSource,
            Publisher publisher,
            RetryScheduler retryScheduler,
            String instanceId,
            Duration leaseDuration,
            int maxAttempts,
            int recoveryLimit,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Clock clock
    ) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(
                dataSource, "位置实时 Outbox 数据源不能为空"));
        this.publisher = Objects.requireNonNull(publisher, "位置实时发布器不能为空");
        this.retryScheduler = Objects.requireNonNull(
                retryScheduler, "位置实时重试调度器不能为空");
        this.instanceId = requireText(instanceId, "位置实时 Outbox 实例标识");
        this.leaseDuration = requirePositive(leaseDuration, "位置实时 Outbox 租约");
        this.retryBaseDelay = requirePositive(
                retryBaseDelay, "位置实时重试基础间隔");
        this.retryMaxDelay = requirePositive(retryMaxDelay, "位置实时重试最大间隔");
        if (retryBaseDelay.compareTo(retryMaxDelay) > 0) {
            throw new IllegalArgumentException("位置实时重试基础间隔不能大于最大间隔");
        }
        if (maxAttempts < 1 || recoveryLimit < 1 || recoveryLimit > 100_000) {
            throw new IllegalArgumentException("位置实时尝试次数或恢复上限不合法");
        }
        this.maxAttempts = maxAttempts;
        this.recoveryLimit = recoveryLimit;
        this.clock = Objects.requireNonNull(clock, "位置实时 Outbox 时钟不能为空");
    }

    /**
     * 精确认领并发布一个已提交事件；其他实例持有有效租约时直接返回。
     *
     * @param eventId 原始定位观测标识
     */
    public void publishById(UUID eventId) {
        claimById(eventId).ifPresent(this::publishClaimed);
    }

    /**
     * 有界扫描待发布、失败或租约过期事件。
     *
     * @return 本次发现的可恢复事件数
     */
    public int recoverBounded() {
        List<UUID> eventIds = jdbcTemplate.queryForList("""
                SELECT event_id
                FROM location_realtime_outbox
                WHERE (
                    (status IN ('PENDING', 'FAILED')
                        AND next_attempt_at <= CURRENT_TIMESTAMP)
                    OR (status = 'PUBLISHING'
                        AND lease_expires_at <= CURRENT_TIMESTAMP)
                )
                ORDER BY occurred_at, ingest_sequence
                LIMIT ?
                """, UUID.class, recoveryLimit);
        eventIds.forEach(this::publishById);
        return eventIds.size();
    }

    private Optional<ClaimedEvent> claimById(UUID eventId) {
        Objects.requireNonNull(eventId, "位置实时事件标识不能为空");
        List<ClaimedEvent> claimed = jdbcTemplate.query("""
                UPDATE location_realtime_outbox
                   SET status = 'PUBLISHING',
                       attempts = attempts + 1,
                       locked_by = ?,
                       locked_at = CURRENT_TIMESTAMP,
                       lease_expires_at = CURRENT_TIMESTAMP
                           + (? * INTERVAL '1 millisecond'),
                       last_error = NULL
                 WHERE event_id = ?
                   AND event_type = 'realtime.location.updated'
                   AND schema_version = 1
                   AND (
                       (status IN ('PENDING', 'FAILED')
                           AND next_attempt_at <= CURRENT_TIMESTAMP)
                       OR (status = 'PUBLISHING'
                           AND lease_expires_at <= CURRENT_TIMESTAMP)
                   )
                RETURNING event_id, ingest_sequence, event_type, schema_version,
                          payload::TEXT, payload_hash, headers::TEXT,
                          attempts, occurred_at
                """, this::mapEvent, instanceId, leaseDuration.toMillis(), eventId);
        return claimed.stream().findFirst();
    }

    private void publishClaimed(ClaimedEvent event) {
        try {
            publisher.publish(event);
            requireSingleUpdate(jdbcTemplate.update("""
                    UPDATE location_realtime_outbox
                       SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                           locked_by = NULL, locked_at = NULL, lease_expires_at = NULL,
                           last_error = NULL
                     WHERE event_id = ? AND status = 'PUBLISHING' AND locked_by = ?
                    """, event.eventId(), instanceId), event.eventId(), "发布成功");
        } catch (RuntimeException exception) {
            String error = safeError(exception);
            if (event.attempts() >= maxAttempts) {
                markDead(event.eventId(), error);
                LOGGER.error("位置实时 Outbox 超过尝试上限并进入死信: eventId={}, error={}",
                        event.eventId(), error);
                return;
            }
            Instant retryAt = clock.instant().plus(retryDelay(event.attempts()));
            markFailed(event.eventId(), retryAt, error);
            retryScheduler.schedule(event.eventId(), retryAt);
            LOGGER.warn("位置实时 Outbox 发布失败并已安排精确重试: eventId={}, error={}",
                    event.eventId(), error);
        }
    }

    private void markFailed(UUID eventId, Instant retryAt, String error) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE location_realtime_outbox
                   SET status = 'FAILED', next_attempt_at = ?, last_error = ?,
                       locked_by = NULL, locked_at = NULL, lease_expires_at = NULL
                 WHERE event_id = ? AND status = 'PUBLISHING' AND locked_by = ?
                """, retryAt.atOffset(java.time.ZoneOffset.UTC), error, eventId, instanceId),
                eventId, "记录发布失败");
    }

    private void markDead(UUID eventId, String error) {
        requireSingleUpdate(jdbcTemplate.update("""
                UPDATE location_realtime_outbox
                   SET status = 'DEAD', last_error = ?,
                       locked_by = NULL, locked_at = NULL, lease_expires_at = NULL
                 WHERE event_id = ? AND status = 'PUBLISHING' AND locked_by = ?
                """, error, eventId, instanceId), eventId, "转入死信");
    }

    private ClaimedEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClaimedEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getLong("ingest_sequence"),
                resultSet.getString("event_type"),
                resultSet.getInt("schema_version"),
                resultSet.getString("payload"), resultSet.getString("payload_hash"),
                resultSet.getString("headers"), resultSet.getInt("attempts"),
                resultSet.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant());
    }

    private Duration retryDelay(int attempts) {
        long exponent = Math.min(attempts - 1L, 20L);
        long multiplier = 1L << exponent;
        long millis;
        try {
            millis = Math.multiplyExact(retryBaseDelay.toMillis(), multiplier);
        } catch (ArithmeticException exception) {
            millis = retryMaxDelay.toMillis();
        }
        return Duration.ofMillis(Math.min(millis, retryMaxDelay.toMillis()));
    }

    private String safeError(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
        String singleLine = message.replaceAll("[\\r\\n\\t]+", " ");
        String redacted = SECRET.matcher(singleLine).replaceAll("$1=[REDACTED]");
        return redacted.length() <= 2_000 ? redacted : redacted.substring(0, 2_000);
    }

    private void requireSingleUpdate(int updated, UUID eventId, String operation) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "位置实时 Outbox 状态已变化，无法" + operation + ": " + eventId);
        }
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(label + "长度不合法");
        }
        return value;
    }

    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return value;
    }

    /** 等待 RabbitMQ publisher confirm 的位置实时事件发布器。 */
    @FunctionalInterface
    public interface Publisher {
        /** @param event 已认领且等待确认的位置实时事件 */
        void publish(ClaimedEvent event);
    }

    /** 仅调度一个已知事件标识的进程内重试器。 */
    @FunctionalInterface
    public interface RetryScheduler {
        /** 在指定时间重新唤醒同一事件。 */
        void schedule(UUID eventId, Instant retryAt);
    }

    /**
     * 当前实例持有租约的不可变位置实时事件。
     *
     * @param eventId 原始定位观测标识
     * @param ingestSequence 已提交事实摄取序号
     * @param eventType 固定位置实时事件类型
     * @param schemaVersion 固定结构版本
     * @param payloadJson 完整业务载荷
     * @param payloadHash 规范载荷摘要
     * @param headersJson 有界消息头
     * @param attempts 包含当前认领在内的尝试次数
     * @param occurredAt 位置事实发生时间
     */
    public record ClaimedEvent(
            UUID eventId,
            long ingestSequence,
            String eventType,
            int schemaVersion,
            String payloadJson,
            String payloadHash,
            String headersJson,
            int attempts,
            Instant occurredAt
    ) {
        /** 校验可靠发布所需字段完整。 */
        public ClaimedEvent {
            Objects.requireNonNull(eventId, "位置实时事件标识不能为空");
            if (ingestSequence < 1L
                    || !"realtime.location.updated".equals(eventType)
                    || schemaVersion != 1 || attempts < 1) {
                throw new IllegalArgumentException("位置实时 Outbox 元数据不合法");
            }
            if (payloadJson == null || payloadJson.isBlank()
                    || payloadHash == null || payloadHash.isBlank()
                    || headersJson == null || headersJson.isBlank()) {
                throw new IllegalArgumentException("位置实时 Outbox 载荷不能为空");
            }
            Objects.requireNonNull(occurredAt, "位置实时事件发生时间不能为空");
        }
    }
}
