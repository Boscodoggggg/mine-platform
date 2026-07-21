package com.bosco.mine.server.modules.mine.operation.runtime;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 持久接收空间业务事件，并按业务时间提供同主体串行、可恢复的租约认领。
 */
@Repository
public class OperationEventQueue {

    private static final String CONSUMER_NAME = "mine-server-operation-spatial";
    private static final int MAX_PAYLOAD_BYTES = 65_536;
    private static final int MAX_ERROR_LENGTH = 2_000;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|token|secret|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建运营空间事件持久队列。
     *
     * @param jdbcTemplate 管理数据库访问模板
     */
    public OperationEventQueue(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "数据库访问模板不能为空");
    }

    /**
     * 在同一事务登记 Inbox 并保存规范化空间事件；同标识异载荷固定拒绝。
     *
     * @param event 已验证空间事件
     * @return 首次接收或幂等重复
     */
    @Transactional
    public EnqueueResult enqueue(RealtimeSpatialEvent event) {
        Objects.requireNonNull(event, "运营空间事件不能为空");
        String payloadJson = canonicalJson(event);
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IdentityConflictException("空间事件载荷超过固定上限");
        }
        validateIdentity(event);
        int inboxInserted = jdbcTemplate.update("""
                INSERT INTO sys_inbox_event (consumer_name, event_id, result)
                VALUES (?, ?, 'SUCCESS')
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, CONSUMER_NAME, event.eventId());
        if (inboxInserted == 0) {
            return samePersistedEvent(event.eventId(), payloadJson)
                    ? EnqueueResult.DUPLICATE
                    : rejectConflictingReplay();
        }
        int inserted = jdbcTemplate.update("""
                INSERT INTO mine_operation_spatial_event_queue (
                    id, event_id, organization_id, site_id,
                    vehicle_id, location_tag_id,
                    zone_type, zone_id, zone_version_id, event_type,
                    occurred_at, ingest_sequence, payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                """, idGenerator.next(), event.eventId(), event.organizationId(), event.siteId(),
                event.vehicleId(), event.tagId(), event.zoneType().name(), event.zoneId(),
                event.zoneVersionId(), event.eventType().name(), Timestamp.from(event.occurredAt()),
                event.ingestSequence(), payloadJson);
        if (inserted != 1) {
            throw new IllegalStateException("运营空间事件未能持久入队");
        }
        return EnqueueResult.ACCEPTED;
    }

    /**
     * 原子认领全局最早且已越过乱序缓冲的事件，
     * 同一车辆或未解析标签只允许一个有效租约。
     *
     * @param policy 排序、租约和退避策略
     * @return 当前可认领事件
     */
    @Transactional
    public Optional<ClaimedEvent> claimNext(ClaimPolicy policy) {
        Objects.requireNonNull(policy, "运营事件认领策略不能为空");
        closeExhaustedClaims(policy.maxAttempts());
        UUID leaseToken = UUID.randomUUID();
        return jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT queue.id
                    FROM mine_operation_spatial_event_queue queue
                    WHERE (
                        (queue.status IN ('PENDING', 'FAILED')
                            AND queue.next_attempt_at <= CURRENT_TIMESTAMP)
                        OR (queue.status = 'PROCESSING'
                            AND queue.leased_until <= CURRENT_TIMESTAMP)
                    )
                      AND queue.attempt_count < ?
                      AND (
                        queue.occurred_at <= (
                            SELECT MAX(latest.occurred_at)
                            FROM mine_operation_spatial_event_queue latest
                            WHERE (
                                (queue.vehicle_id IS NOT NULL
                                    AND latest.vehicle_id = queue.vehicle_id)
                                OR (queue.vehicle_id IS NULL
                                    AND latest.vehicle_id IS NULL
                                    AND latest.location_tag_id = queue.location_tag_id)
                            )
                        ) - (? * INTERVAL '1 millisecond')
                        OR queue.received_at <= CURRENT_TIMESTAMP
                            - (? * INTERVAL '1 millisecond')
                      )
                      AND NOT EXISTS (
                        SELECT 1
                        FROM mine_operation_spatial_event_queue active
                        WHERE active.id <> queue.id
                          AND active.status = 'PROCESSING'
                          AND active.leased_until > CURRENT_TIMESTAMP
                          AND (
                            (queue.vehicle_id IS NOT NULL
                                AND active.vehicle_id = queue.vehicle_id)
                            OR (queue.vehicle_id IS NULL
                                AND active.vehicle_id IS NULL
                                AND active.location_tag_id = queue.location_tag_id)
                          )
                      )
                      AND NOT EXISTS (
                        SELECT 1
                        FROM mine_operation_spatial_event_queue earlier
                        WHERE earlier.id <> queue.id
                          AND earlier.status NOT IN ('PROCESSED', 'DEAD')
                          AND (
                            (queue.vehicle_id IS NOT NULL
                                AND earlier.vehicle_id = queue.vehicle_id)
                            OR (queue.vehicle_id IS NULL
                                AND earlier.vehicle_id IS NULL
                                AND earlier.location_tag_id = queue.location_tag_id)
                          )
                          AND (earlier.occurred_at, earlier.ingest_sequence, earlier.event_id)
                              < (queue.occurred_at, queue.ingest_sequence, queue.event_id)
                      )
                    ORDER BY queue.occurred_at, queue.ingest_sequence, queue.event_id
                    FOR UPDATE OF queue SKIP LOCKED
                    LIMIT 1
                ), claimed AS (
                    UPDATE mine_operation_spatial_event_queue target
                    SET status = 'PROCESSING',
                        attempt_count = target.attempt_count + 1,
                        lease_token = ?,
                        leased_until = CURRENT_TIMESTAMP
                            + (? * INTERVAL '1 millisecond'),
                        last_error = NULL
                    FROM candidate
                    WHERE target.id = candidate.id
                    RETURNING target.id, target.event_id, target.organization_id,
                              target.site_id, target.vehicle_id, target.location_tag_id,
                              target.occurred_at, target.ingest_sequence,
                              target.payload::TEXT AS payload_json,
                              target.attempt_count, target.lease_token, target.leased_until
                )
                SELECT claimed.*,
                       EXISTS (
                           SELECT 1
                           FROM mine_operation_spatial_event_queue frontier
                           WHERE frontier.status = 'PROCESSED'
                             AND (
                                (claimed.vehicle_id IS NOT NULL
                                    AND frontier.vehicle_id = claimed.vehicle_id)
                                OR (claimed.vehicle_id IS NULL
                                    AND frontier.vehicle_id IS NULL
                                    AND frontier.location_tag_id = claimed.location_tag_id)
                             )
                             AND (frontier.occurred_at, frontier.ingest_sequence, frontier.event_id)
                                 > (claimed.occurred_at, claimed.ingest_sequence, claimed.event_id)
                       ) AS too_late
                FROM claimed
                """, this::mapClaimedEvent,
                policy.maxAttempts(), policy.allowedLateness().toMillis(),
                policy.idleFlush().toMillis(),
                leaseToken, policy.leaseDuration().toMillis())
                .stream()
                .findFirst();
    }

    private void closeExhaustedClaims(int maxAttempts) {
        jdbcTemplate.update("""
                UPDATE mine_operation_spatial_event_queue
                SET status = 'DEAD', next_attempt_at = CURRENT_TIMESTAMP,
                    lease_token = NULL, leased_until = NULL,
                    last_error = CASE
                        WHEN last_error IS NULL OR btrim(last_error) = ''
                            THEN 'LeaseExpired: 已达到最大处理尝试次数'
                        ELSE last_error
                    END
                WHERE attempt_count >= ?
                  AND (
                    (status = 'PROCESSING' AND leased_until <= CURRENT_TIMESTAMP)
                    OR (status = 'FAILED' AND next_attempt_at <= CURRENT_TIMESTAMP)
                  )
                """, maxAttempts);
    }

    /**
     * 仅允许当前且未过期租约把事件推进为已处理。
     *
     * @param event 已认领事件
     * @return 是否完成当前租约
     */
    @Transactional
    public boolean complete(ClaimedEvent event) {
        Objects.requireNonNull(event, "待完成运营事件不能为空");
        return jdbcTemplate.update("""
                UPDATE mine_operation_spatial_event_queue
                SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP,
                    lease_token = NULL, leased_until = NULL, last_error = NULL
                WHERE id = ? AND event_id = ?
                  AND status = 'PROCESSING' AND lease_token = ?
                  AND leased_until > CURRENT_TIMESTAMP
                """, event.queueId(), event.eventId(), event.leaseToken()) == 1;
    }

    /**
     * 使用当前租约记录脱敏失败摘要，并按有界指数退避重试或进入 DEAD。
     *
     * @param event 已认领事件
     * @param failure 处理异常
     * @param policy 排序、租约和退避策略
     * @return 失败处置结果
     */
    @Transactional
    public FailureResult fail(
            ClaimedEvent event,
            RuntimeException failure,
            ClaimPolicy policy
    ) {
        Objects.requireNonNull(event, "待失败运营事件不能为空");
        Objects.requireNonNull(failure, "运营事件失败原因不能为空");
        Objects.requireNonNull(policy, "运营事件认领策略不能为空");
        boolean dead = event.attemptCount() >= policy.maxAttempts();
        Duration delay = retryDelay(event.attemptCount(), policy);
        int changed = jdbcTemplate.update("""
                UPDATE mine_operation_spatial_event_queue
                SET status = ?, next_attempt_at = CURRENT_TIMESTAMP
                        + (? * INTERVAL '1 millisecond'),
                    lease_token = NULL, leased_until = NULL, last_error = ?
                WHERE id = ? AND event_id = ?
                  AND status = 'PROCESSING' AND lease_token = ?
                  AND leased_until > CURRENT_TIMESTAMP
                """, dead ? "DEAD" : "FAILED", dead ? 0L : delay.toMillis(),
                safeError(failure), event.queueId(), event.eventId(), event.leaseToken());
        if (changed == 0) {
            return FailureResult.LEASE_LOST;
        }
        return dead ? FailureResult.DEAD : FailureResult.RETRY_SCHEDULED;
    }

    private void validateIdentity(RealtimeSpatialEvent event) {
        boolean valid = exists("""
                SELECT EXISTS (
                    SELECT 1 FROM mine_site
                    WHERE id = ? AND organization_id = ?
                )
                """, event.siteId(), event.organizationId())
                && exists("""
                SELECT EXISTS (
                    SELECT 1 FROM mine_location_tag
                    WHERE id = ? AND organization_id = ? AND site_id = ?
                )
                """, event.tagId(), event.organizationId(), event.siteId());
        if (event.vehicleId() != null) {
            valid = valid && exists("""
                    SELECT EXISTS (
                        SELECT 1 FROM mine_vehicle
                        WHERE id = ? AND organization_id = ? AND site_id = ?
                    )
                    """, event.vehicleId(), event.organizationId(), event.siteId());
        }
        if (event.personId() != null) {
            valid = valid && exists("""
                    SELECT EXISTS (
                        SELECT 1 FROM mine_person
                        WHERE id = ? AND organization_id = ?
                    )
                    """, event.personId(), event.organizationId());
        }
        valid = valid && validateZone(event);
        if (!valid) {
            throw new IdentityConflictException("空间事件引用了不一致的业务身份");
        }
    }

    private boolean validateZone(RealtimeSpatialEvent event) {
        if (event.zoneType() == RealtimeSpatialEvent.ZoneType.GEOFENCE) {
            return exists("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM mine_geofence_version version
                        WHERE version.id = ? AND version.geofence_id = ?
                          AND version.organization_id = ? AND version.site_id = ?
                          AND version.status = 'PUBLISHED'
                    )
                    """, event.zoneVersionId(), event.zoneId(),
                    event.organizationId(), event.siteId());
        }
        return exists("""
                SELECT EXISTS (
                    SELECT 1
                    FROM mine_waypoint_version version
                    WHERE version.id = ? AND version.waypoint_id = ?
                      AND version.organization_id = ? AND version.site_id = ?
                      AND version.status = 'PUBLISHED'
                )
                """, event.zoneVersionId(), event.zoneId(),
                event.organizationId(), event.siteId());
    }

    private boolean exists(String sql, Object... parameters) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, parameters));
    }

    private boolean samePersistedEvent(UUID eventId, String payloadJson) {
        return Boolean.TRUE.equals(jdbcTemplate.query("""
                SELECT payload = CAST(? AS JSONB) AS same_payload
                FROM mine_operation_spatial_event_queue
                WHERE event_id = ?
                """, (resultSet, rowNumber) -> resultSet.getBoolean("same_payload"),
                payloadJson, eventId).stream().findFirst().orElse(false));
    }

    private EnqueueResult rejectConflictingReplay() {
        throw new IdentityConflictException("同一空间事件标识出现冲突载荷");
    }

    private ClaimedEvent mapClaimedEvent(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String payloadJson = resultSet.getString("payload_json");
        try {
            return new ClaimedEvent(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getObject("organization_id", UUID.class),
                    resultSet.getObject("site_id", UUID.class),
                    resultSet.getObject("vehicle_id", UUID.class),
                    resultSet.getObject("location_tag_id", UUID.class),
                    resultSet.getTimestamp("occurred_at").toInstant(),
                    resultSet.getLong("ingest_sequence"),
                    resultSet.getInt("attempt_count"),
                    resultSet.getObject("lease_token", UUID.class),
                    resultSet.getTimestamp("leased_until").toInstant(),
                    resultSet.getBoolean("too_late"),
                    jsonMapper.readValue(payloadJson, RealtimeSpatialEvent.class));
        } catch (JacksonException exception) {
            throw new SQLException("持久运营空间事件载荷无法解析", exception);
        }
    }

    private String canonicalJson(Object value) {
        try {
            Object tree = jsonMapper.treeToValue(jsonMapper.valueToTree(value), Object.class);
            return jsonMapper.writeValueAsString(canonicalize(tree));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("运营空间事件无法规范化", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(
                    String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private Duration retryDelay(int attemptCount, ClaimPolicy policy) {
        Duration delay = policy.retryBaseDelay();
        for (int index = 1; index < attemptCount; index++) {
            if (delay.compareTo(policy.retryMaxDelay().dividedBy(2)) > 0) {
                return policy.retryMaxDelay();
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(policy.retryMaxDelay()) > 0
                ? policy.retryMaxDelay() : delay;
    }

    private String safeError(RuntimeException failure) {
        String message = failure.getMessage();
        String firstLine = message == null ? "无错误摘要" : message.split("[\\r\\n]", 2)[0];
        String sanitized = SECRET.matcher(firstLine).replaceAll("$1=[REDACTED]")
                .replaceAll("\\s+", " ").strip();
        String result = failure.getClass().getSimpleName() + ": "
                + (sanitized.isEmpty() ? "无错误摘要" : sanitized);
        return result.length() <= MAX_ERROR_LENGTH
                ? result : result.substring(0, MAX_ERROR_LENGTH);
    }

    /** 空间事件入队结果。 */
    public enum EnqueueResult {
        /** 首次持久接收。 */
        ACCEPTED,
        /** 相同事件的幂等重投。 */
        DUPLICATE
    }

    /** 失败租约的持久处置结果。 */
    public enum FailureResult {
        /** 已按退避时间等待重试。 */
        RETRY_SCHEDULED,
        /** 已达到尝试上限并保留 DEAD 证据。 */
        DEAD,
        /** 当前执行器已不再持有有效租约。 */
        LEASE_LOST
    }

    /**
     * 事件时间缓冲、处理租约和失败退避策略。
     *
     * @param allowedLateness 允许乱序窗口
     * @param idleFlush 无新事件后的排空等待
     * @param leaseDuration 单次处理租约
     * @param maxAttempts 最大处理尝试次数
     * @param retryBaseDelay 首次失败退避
     * @param retryMaxDelay 最大失败退避
     */
    public record ClaimPolicy(
            Duration allowedLateness,
            Duration idleFlush,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay
    ) {
        /** 校验运行策略保持有界且可执行。 */
        public ClaimPolicy {
            allowedLateness = requireDuration(
                    allowedLateness, true, "允许乱序窗口");
            idleFlush = requireDuration(idleFlush, false, "空闲排空窗口");
            leaseDuration = requireDuration(leaseDuration, false, "处理租约");
            retryBaseDelay = requireDuration(retryBaseDelay, false, "基础退避");
            retryMaxDelay = requireDuration(retryMaxDelay, false, "最大退避");
            if (maxAttempts < 1 || maxAttempts > 100
                    || retryBaseDelay.compareTo(retryMaxDelay) > 0) {
                throw new IllegalArgumentException("运营事件尝试次数或退避范围不合法");
            }
        }

        private static Duration requireDuration(
                Duration value,
                boolean allowZero,
                String name
        ) {
            if (value == null || value.isNegative() || (!allowZero && value.isZero())
                    || value.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException(name + "必须在允许范围内");
            }
            return value;
        }
    }

    /**
     * 受随机租约保护的空间事件处理上下文。
     *
     * @param queueId 队列记录标识
     * @param eventId 空间事件标识
     * @param organizationId 组织标识
     * @param siteId 矿区标识
     * @param vehicleId 可选车辆标识
     * @param locationTagId 定位标签标识
     * @param occurredAt 业务发生时间
     * @param ingestSequence 摄入序号
     * @param attemptCount 当前尝试次数
     * @param leaseToken 当前随机租约令牌
     * @param leasedUntil 租约到期时间
     * @param tooLate 是否落后于最终处理前沿
     * @param payload 强类型空间事件载荷
     */
    public record ClaimedEvent(
            UUID queueId,
            UUID eventId,
            UUID organizationId,
            UUID siteId,
            UUID vehicleId,
            UUID locationTagId,
            Instant occurredAt,
            long ingestSequence,
            int attemptCount,
            UUID leaseToken,
            Instant leasedUntil,
            boolean tooLate,
            RealtimeSpatialEvent payload
    ) {
    }

    /** 表示不可信、跨作用域或冲突的空间事件身份。 */
    public static final class IdentityConflictException extends IllegalArgumentException {
        /** 创建不包含原始载荷的固定身份错误。 */
        public IdentityConflictException(String message) {
            super(message);
        }
    }
}
