package com.bosco.mine.telemetry.spatial;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 使用带防旧令牌的数据库租约认领空间 Outbox，并在 Broker 确认后推进发布状态。
 */
public final class SpatialEventOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpatialEventOutboxRelay.class);
    private static final String EVENT_TYPE = "realtime.spatial.event.created";
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|token|secret|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final JdbcTemplate jdbc;
    private final Publisher publisher;
    private final RetryScheduler retryScheduler;
    private final Executor dispatchExecutor;
    private final String instanceId;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final int recoveryLimit;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final Clock clock;
    private final Set<UUID> dispatching = ConcurrentHashMap.newKeySet();

    /**
     * 创建带发布确认、指数退避、DEAD 和有界恢复能力的空间事件中继器。
     *
     * @param dataSource Telemetry 时序数据库
     * @param publisher 等待 RabbitMQ 发布确认的发布器
     * @param retryScheduler 精确事件重试调度器
     * @param instanceId 当前服务实例标识
     * @param leaseDuration 发布认领租约
     * @param maxAttempts 最大发布尝试次数
     * @param recoveryLimit 单次恢复扫描上限
     * @param retryBaseDelay 首次失败退避时长
     * @param retryMaxDelay 最大失败退避时长
     * @param clock 平台可信时钟
     */
    public SpatialEventOutboxRelay(
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
        this(dataSource, publisher, retryScheduler, Runnable::run,
                instanceId, leaseDuration, maxAttempts, recoveryLimit,
                retryBaseDelay, retryMaxDelay, clock);
    }

    /**
     * 创建使用有界执行器并发恢复空间 Outbox 的生产中继器。
     *
     * @param dataSource Telemetry 时序数据库
     * @param publisher 等待 RabbitMQ 发布确认的发布器
     * @param retryScheduler 精确事件重试调度器
     * @param dispatchExecutor 空间事件有界发布执行器
     * @param instanceId 当前服务实例标识
     * @param leaseDuration 发布认领租约
     * @param maxAttempts 最大发布尝试次数
     * @param recoveryLimit 单次恢复扫描上限
     * @param retryBaseDelay 首次失败退避时长
     * @param retryMaxDelay 最大失败退避时长
     * @param clock 平台可信时钟
     */
    public SpatialEventOutboxRelay(
            DataSource dataSource,
            Publisher publisher,
            RetryScheduler retryScheduler,
            Executor dispatchExecutor,
            String instanceId,
            Duration leaseDuration,
            int maxAttempts,
            int recoveryLimit,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Clock clock
    ) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(
                dataSource, "空间 Outbox 数据源不能为空"));
        this.publisher = Objects.requireNonNull(publisher, "空间事件发布器不能为空");
        this.retryScheduler = Objects.requireNonNull(
                retryScheduler, "空间事件重试调度器不能为空");
        this.dispatchExecutor = Objects.requireNonNull(
                dispatchExecutor, "空间事件发布执行器不能为空");
        this.instanceId = requireText(instanceId, "空间 Outbox 实例标识");
        this.leaseDuration = requirePositive(leaseDuration, "空间 Outbox 发布租约");
        this.retryBaseDelay = requirePositive(retryBaseDelay, "空间 Outbox 基础退避");
        this.retryMaxDelay = requirePositive(retryMaxDelay, "空间 Outbox 最大退避");
        if (retryBaseDelay.compareTo(retryMaxDelay) > 0) {
            throw new IllegalArgumentException("空间 Outbox 基础退避不能大于最大退避");
        }
        if (maxAttempts < 1 || maxAttempts > 100
                || recoveryLimit < 1 || recoveryLimit > 100_000) {
            throw new IllegalArgumentException("空间 Outbox 尝试次数或恢复上限不合法");
        }
        this.maxAttempts = maxAttempts;
        this.recoveryLimit = recoveryLimit;
        this.clock = Objects.requireNonNull(clock, "空间 Outbox 时钟不能为空");
    }

    /**
     * 精确认领并发布一个已提交空间事件；已发布或被有效租约持有时直接返回。
     *
     * @param eventId 空间业务事件标识
     */
    public void publishById(UUID eventId) {
        claimById(eventId).ifPresent(this::publishClaimed);
    }

    /**
     * 有界扫描待发布、失败和租约过期空间事件。
     *
     * @return 本轮发现的可恢复事件数量
     */
    public int recoverBounded() {
        List<UUID> eventIds = jdbc.queryForList("""
                SELECT event_id
                FROM spatial_event_outbox
                WHERE event_type = 'realtime.spatial.event.created'
                  AND schema_version = 1
                  AND (
                    (status IN ('PENDING', 'FAILED')
                        AND next_attempt_at <= CURRENT_TIMESTAMP)
                    OR (status = 'PUBLISHING'
                        AND lease_expires_at <= CURRENT_TIMESTAMP)
                  )
                ORDER BY occurred_at, event_id
                LIMIT ?
                """, UUID.class, recoveryLimit);
        eventIds.forEach(this::dispatch);
        return eventIds.size();
    }

    private void dispatch(UUID eventId) {
        if (!dispatching.add(eventId)) {
            return;
        }
        try {
            dispatchExecutor.execute(() -> {
                try {
                    publishById(eventId);
                } finally {
                    dispatching.remove(eventId);
                }
            });
        } catch (RejectedExecutionException exception) {
            dispatching.remove(eventId);
            LOGGER.warn("空间 Outbox 发布执行器已饱和，本轮保留待恢复事件: eventId={}", eventId);
        }
    }

    private Optional<ClaimedEvent> claimById(UUID eventId) {
        Objects.requireNonNull(eventId, "空间事件标识不能为空");
        UUID leaseToken = UUID.randomUUID();
        List<ClaimedEvent> claimed = jdbc.query("""
                UPDATE spatial_event_outbox
                   SET status = 'PUBLISHING', attempts = attempts + 1,
                       lease_token = ?, locked_by = ?, locked_at = CURRENT_TIMESTAMP,
                       lease_expires_at = CURRENT_TIMESTAMP
                           + (? * INTERVAL '1 millisecond'),
                       last_error = NULL
                 WHERE event_id = ?
                   AND event_type = 'realtime.spatial.event.created'
                   AND schema_version = 1
                   AND (
                     (status IN ('PENDING', 'FAILED')
                         AND next_attempt_at <= CURRENT_TIMESTAMP)
                     OR (status = 'PUBLISHING'
                         AND lease_expires_at <= CURRENT_TIMESTAMP)
                   )
                RETURNING event_id, event_type, schema_version, payload::TEXT,
                          payload_hash, headers::TEXT, attempts, occurred_at,
                          lease_token
                """, this::mapEvent, leaseToken, instanceId,
                leaseDuration.toMillis(), eventId);
        return claimed.stream().findFirst();
    }

    private void publishClaimed(ClaimedEvent event) {
        try {
            publisher.publish(event);
            requireSingleUpdate(jdbc.update("""
                    UPDATE spatial_event_outbox
                       SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                           lease_token = NULL, locked_by = NULL, locked_at = NULL,
                           lease_expires_at = NULL, last_error = NULL
                     WHERE event_id = ? AND status = 'PUBLISHING'
                       AND locked_by = ? AND lease_token = ?
                       AND lease_expires_at > CURRENT_TIMESTAMP
                    """, event.eventId(), instanceId, event.leaseToken()),
                    event.eventId(), "发布成功");
        } catch (RuntimeException exception) {
            String error = safeError(exception);
            if (event.attempts() >= maxAttempts) {
                markDead(event, error);
                LOGGER.error("空间 Outbox 超过尝试上限并进入死信: eventId={}, error={}",
                        event.eventId(), error);
                return;
            }
            Instant retryAt = clock.instant().plus(retryDelay(event.attempts()));
            markFailed(event, retryAt, error);
            retryScheduler.schedule(event.eventId(), retryAt);
            LOGGER.warn("空间 Outbox 发布失败并已安排精确重试: eventId={}, error={}",
                    event.eventId(), error);
        }
    }

    private void markFailed(ClaimedEvent event, Instant retryAt, String error) {
        requireSingleUpdate(jdbc.update("""
                UPDATE spatial_event_outbox
                   SET status = 'FAILED', next_attempt_at = ?, last_error = ?,
                       lease_token = NULL, locked_by = NULL, locked_at = NULL,
                       lease_expires_at = NULL
                 WHERE event_id = ? AND status = 'PUBLISHING'
                   AND locked_by = ? AND lease_token = ?
                   AND lease_expires_at > CURRENT_TIMESTAMP
                """, retryAt.atOffset(java.time.ZoneOffset.UTC), error,
                event.eventId(), instanceId, event.leaseToken()),
                event.eventId(), "记录发布失败");
    }

    private void markDead(ClaimedEvent event, String error) {
        requireSingleUpdate(jdbc.update("""
                UPDATE spatial_event_outbox
                   SET status = 'DEAD', last_error = ?,
                       lease_token = NULL, locked_by = NULL, locked_at = NULL,
                       lease_expires_at = NULL
                 WHERE event_id = ? AND status = 'PUBLISHING'
                   AND locked_by = ? AND lease_token = ?
                   AND lease_expires_at > CURRENT_TIMESTAMP
                """, error, event.eventId(), instanceId, event.leaseToken()),
                event.eventId(), "转入死信");
    }

    private ClaimedEvent mapEvent(ResultSet rs, int rowNumber) throws SQLException {
        return new ClaimedEvent(
                rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                rs.getInt("schema_version"), rs.getString("payload"),
                rs.getString("payload_hash"), rs.getString("headers"),
                rs.getInt("attempts"),
                rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("lease_token", UUID.class));
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
                    "空间 Outbox 租约已变化，无法" + operation + ": " + eventId);
        }
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

    /** 等待 RabbitMQ publisher confirm 的空间事件发布器。 */
    @FunctionalInterface
    public interface Publisher {
        /** @param event 已持有发布租约的空间事件 */
        void publish(ClaimedEvent event);
    }

    /** 仅调度一个已知空间事件标识的进程内重试器。 */
    @FunctionalInterface
    public interface RetryScheduler {
        /** 在指定时间重新唤醒同一空间事件。 */
        void schedule(UUID eventId, Instant retryAt);
    }

    /**
     * 当前实例持有带防旧令牌租约的不可变空间 Outbox 事件。
     *
     * @param eventId 空间业务事件标识
     * @param eventType 固定空间实时事件类型
     * @param schemaVersion 固定载荷版本
     * @param payloadJson 完整业务载荷 JSON
     * @param payloadHash 规范载荷摘要
     * @param headersJson 有界分区消息头 JSON
     * @param attempts 包含当前认领的尝试次数
     * @param occurredAt 空间业务事件发生时间
     * @param leaseToken 本次发布认领的防旧执行令牌
     */
    public record ClaimedEvent(
            UUID eventId,
            String eventType,
            int schemaVersion,
            String payloadJson,
            String payloadHash,
            String headersJson,
            int attempts,
            Instant occurredAt,
            UUID leaseToken
    ) {

        /** 校验可靠发布所需元数据完整且属于白名单。 */
        public ClaimedEvent {
            Objects.requireNonNull(eventId, "空间事件标识不能为空");
            if (!EVENT_TYPE.equals(eventType) || schemaVersion != 1 || attempts < 1) {
                throw new IllegalArgumentException("空间 Outbox 元数据不合法");
            }
            if (payloadJson == null || payloadJson.isBlank()
                    || payloadHash == null || payloadHash.isBlank()
                    || headersJson == null || headersJson.isBlank()) {
                throw new IllegalArgumentException("空间 Outbox 载荷不能为空");
            }
            Objects.requireNonNull(occurredAt, "空间事件发生时间不能为空");
            Objects.requireNonNull(leaseToken, "空间 Outbox 租约令牌不能为空");
        }
    }
}
