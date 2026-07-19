package com.bosco.mine.telemetry.spatial;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 持久空间评估队列，提供幂等入队、同标签事件时间排序、租约领取和有界退避。
 */
public final class SpatialEvaluationQueue {

    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,47}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SpatialWatermarkPolicy watermarkPolicy;

    /**
     * 创建使用本地数据库事务和 PostgreSQL 行锁的空间评估队列。
     *
     * @param dataSource      Telemetry 数据源
     * @param watermarkPolicy 事件时间水位策略
     */
    public SpatialEvaluationQueue(DataSource dataSource, SpatialWatermarkPolicy watermarkPolicy) {
        Objects.requireNonNull(dataSource, "数据源不能为空");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.watermarkPolicy = Objects.requireNonNull(watermarkPolicy, "水位策略不能为空");
    }

    /**
     * 把已经持久化的位置事实幂等加入空间评估队列并推进标签水位。
     *
     * @param command 入队命令
     * @return 首次入队返回 {@code true}，重复事件返回 {@code false}
     */
    public boolean enqueue(EnqueueCommand command) {
        Objects.requireNonNull(command, "入队命令不能为空");
        Boolean inserted = transactions.execute(status -> {
            lockSubject(command.siteId(), command.tagId());
            Optional<SpatialWatermarkPolicy.Watermark> previous = findWatermark(
                    command.siteId(), command.tagId());
            boolean tooLate = previous
                    .map(watermark -> watermarkPolicy.isTooLate(watermark, command.occurredAt()))
                    .orElse(false);
            int changed = jdbc.update("""
                    INSERT INTO spatial_evaluation_queue (
                        location_event_id, organization_id, site_id, tag_id,
                        occurred_at, ingest_sequence, runtime_revision,
                        status, result_reason, next_attempt_at, enqueued_at, processed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (location_event_id) DO NOTHING
                    """,
                    command.locationEventId(), command.organizationId(), command.siteId(),
                    command.tagId(), sqlTime(command.occurredAt()), command.ingestSequence(),
                    command.runtimeRevision(), tooLate ? "SKIPPED" : "PENDING",
                    tooLate ? "TOO_LATE" : null, sqlTime(command.arrivedAt()),
                    sqlTime(command.arrivedAt()), tooLate ? sqlTime(command.arrivedAt()) : null);
            if (changed == 0) {
                return false;
            }
            SpatialWatermarkPolicy.Watermark observed = previous
                    .map(current -> watermarkPolicy.observe(
                            current, command.occurredAt(), command.ingestSequence(), command.arrivedAt()))
                    .orElseGet(() -> new SpatialWatermarkPolicy.Watermark(
                            command.occurredAt(), command.ingestSequence(), command.arrivedAt()));
            upsertWatermark(command, observed);
            return true;
        });
        return Boolean.TRUE.equals(inserted);
    }

    /**
     * 领取已经越过水位或达到空闲刷新条件的事实。
     *
     * <p>每个标签只领取最早未完成记录；较早记录处于租约或退避中时，后续记录不会越序。</p>
     *
     * @param workerId     领取实例标识
     * @param now          平台可信当前时间
     * @param leaseDuration 租约时长
     * @param limit        单次最大领取数量
     * @return 按事件时间和摄入序号排序的领取结果
     */
    public List<ClaimedItem> claimReady(
            String workerId,
            Instant now,
            Duration leaseDuration,
            int limit
    ) {
        String safeWorkerId = requireText(workerId, "领取实例标识", 128);
        Objects.requireNonNull(now, "当前时间不能为空");
        Duration safeLease = requirePositive(leaseDuration, "租约时长");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("领取数量必须在 1 到 1000 之间");
        }
        List<ClaimedItem> result = transactions.execute(status -> {
            recoverExpiredLeases(now);
            List<ClaimedItem> candidates = jdbc.query("""
                    SELECT q.location_event_id, q.organization_id, q.site_id, q.tag_id,
                           q.occurred_at, q.ingest_sequence, q.runtime_revision, q.attempts
                    FROM spatial_evaluation_queue q
                    JOIN spatial_subject_watermark watermark
                      ON watermark.site_id = q.site_id AND watermark.tag_id = q.tag_id
                    WHERE q.status IN ('PENDING', 'FAILED')
                      AND q.next_attempt_at <= ?
                      AND q.occurred_at <= CASE
                          WHEN ? >= watermark.last_arrival_at
                              + (? * INTERVAL '1 millisecond')
                          THEN watermark.max_occurred_at
                          ELSE watermark.max_occurred_at
                              - (? * INTERVAL '1 millisecond')
                      END
                      AND NOT EXISTS (
                          SELECT 1
                          FROM spatial_evaluation_queue earlier
                          WHERE earlier.site_id = q.site_id
                            AND earlier.tag_id = q.tag_id
                            AND earlier.status IN ('PENDING', 'PROCESSING', 'FAILED')
                            AND (earlier.occurred_at, earlier.ingest_sequence)
                                < (q.occurred_at, q.ingest_sequence)
                      )
                    ORDER BY q.occurred_at, q.ingest_sequence
                    FOR UPDATE OF q SKIP LOCKED
                    LIMIT ?
                    """, (rs, rowNum) -> mapClaimedItem(rs),
                    sqlTime(now), sqlTime(now), watermarkPolicy.idleFlushAfter().toMillis(),
                    watermarkPolicy.allowedLateness().toMillis(), limit);
            Instant leaseExpiresAt = now.plus(safeLease);
            List<ClaimedItem> claimed = new ArrayList<>(candidates.size());
            for (ClaimedItem candidate : candidates) {
                int changed = jdbc.update("""
                        UPDATE spatial_evaluation_queue
                        SET status = 'PROCESSING', result_reason = NULL, attempts = attempts + 1,
                            locked_by = ?, locked_at = ?, lease_expires_at = ?
                        WHERE location_event_id = ?
                          AND status IN ('PENDING', 'FAILED')
                        """, safeWorkerId, sqlTime(now), sqlTime(leaseExpiresAt),
                        candidate.locationEventId());
                if (changed == 1) {
                    claimed.add(candidate.withAttempts(candidate.attempts() + 1));
                }
            }
            return List.copyOf(claimed);
        });
        return result == null ? List.of() : result;
    }

    /**
     * 把当前实例领取的事实标记为处理成功并释放租约。
     *
     * @param locationEventId 位置事实事件标识
     * @param workerId        当前实例标识
     * @param processedAt     完成时间
     */
    public void markProcessed(UUID locationEventId, String workerId, Instant processedAt) {
        Objects.requireNonNull(locationEventId, "位置事实事件标识不能为空");
        String safeWorkerId = requireText(workerId, "处理实例标识", 128);
        Objects.requireNonNull(processedAt, "完成时间不能为空");
        int changed = jdbc.update("""
                UPDATE spatial_evaluation_queue
                SET status = 'PROCESSED', result_reason = NULL, processed_at = ?,
                    locked_by = NULL, locked_at = NULL, lease_expires_at = NULL
                WHERE location_event_id = ? AND status = 'PROCESSING' AND locked_by = ?
                """, sqlTime(processedAt), locationEventId, safeWorkerId);
        requireChanged(changed, "空间评估记录不属于当前实例或已离开处理中状态");
    }

    /**
     * 记录当前处理失败，并按尝试次数退避或进入死信状态。
     *
     * @param locationEventId 位置事实事件标识
     * @param workerId        当前实例标识
     * @param failedAt        失败时间
     * @param baseBackoff     首次失败退避时长
     * @param maxBackoff      最大退避时长
     * @param maxAttempts     最大处理尝试次数
     * @param reasonCode      固定低基数失败原因
     */
    public void markFailed(
            UUID locationEventId,
            String workerId,
            Instant failedAt,
            Duration baseBackoff,
            Duration maxBackoff,
            int maxAttempts,
            String reasonCode
    ) {
        Objects.requireNonNull(locationEventId, "位置事实事件标识不能为空");
        String safeWorkerId = requireText(workerId, "处理实例标识", 128);
        Objects.requireNonNull(failedAt, "失败时间不能为空");
        Duration safeBase = requirePositive(baseBackoff, "基础退避时长");
        Duration safeMax = requirePositive(maxBackoff, "最大退避时长");
        if (safeMax.compareTo(safeBase) < 0) {
            throw new IllegalArgumentException("最大退避时长不能小于基础退避时长");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("最大尝试次数必须在 1 到 100 之间");
        }
        String safeReason = requireReasonCode(reasonCode);
        transactions.executeWithoutResult(status -> {
            Integer attempts = jdbc.query("""
                    SELECT attempts
                    FROM spatial_evaluation_queue
                    WHERE location_event_id = ? AND status = 'PROCESSING' AND locked_by = ?
                    FOR UPDATE
                    """, rs -> rs.next() ? rs.getInt(1) : null,
                    locationEventId, safeWorkerId);
            if (attempts == null) {
                throw new IllegalStateException("空间评估记录不属于当前实例或已离开处理中状态");
            }
            boolean dead = attempts >= maxAttempts;
            Duration backoff = boundedBackoff(safeBase, safeMax, attempts);
            int changed = jdbc.update("""
                    UPDATE spatial_evaluation_queue
                    SET status = ?, result_reason = ?, next_attempt_at = ?, processed_at = ?,
                        locked_by = NULL, locked_at = NULL, lease_expires_at = NULL
                    WHERE location_event_id = ? AND status = 'PROCESSING' AND locked_by = ?
                    """, dead ? "DEAD" : "FAILED", safeReason,
                    sqlTime(failedAt.plus(backoff)), dead ? sqlTime(failedAt) : null,
                    locationEventId, safeWorkerId);
            requireChanged(changed, "空间评估失败状态更新发生并发冲突");
        });
    }

    private void recoverExpiredLeases(Instant now) {
        jdbc.update("""
                UPDATE spatial_evaluation_queue
                SET status = 'FAILED', result_reason = 'LEASE_EXPIRED', next_attempt_at = ?,
                    locked_by = NULL, locked_at = NULL, lease_expires_at = NULL
                WHERE status = 'PROCESSING' AND lease_expires_at <= ?
                """, sqlTime(now), sqlTime(now));
    }

    private void lockSubject(UUID siteId, UUID tagId) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, siteId + ":" + tagId);
                statement.execute();
                return null;
            }
        });
    }

    private Optional<SpatialWatermarkPolicy.Watermark> findWatermark(UUID siteId, UUID tagId) {
        return jdbc.query("""
                SELECT max_occurred_at, max_ingest_sequence, last_arrival_at
                FROM spatial_subject_watermark
                WHERE site_id = ? AND tag_id = ?
                """, rs -> rs.next()
                ? Optional.of(new SpatialWatermarkPolicy.Watermark(
                        rs.getObject("max_occurred_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getLong("max_ingest_sequence"),
                        rs.getObject("last_arrival_at", java.time.OffsetDateTime.class).toInstant()))
                : Optional.empty(), siteId, tagId);
    }

    private void upsertWatermark(
            EnqueueCommand command,
            SpatialWatermarkPolicy.Watermark observed
    ) {
        jdbc.update("""
                INSERT INTO spatial_subject_watermark (
                    organization_id, site_id, tag_id, max_occurred_at,
                    max_ingest_sequence, last_arrival_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (site_id, tag_id) DO UPDATE SET
                    organization_id = EXCLUDED.organization_id,
                    max_occurred_at = EXCLUDED.max_occurred_at,
                    max_ingest_sequence = EXCLUDED.max_ingest_sequence,
                    last_arrival_at = EXCLUDED.last_arrival_at,
                    updated_at = EXCLUDED.updated_at
                """, command.organizationId(), command.siteId(), command.tagId(),
                sqlTime(observed.maxOccurredAt()), observed.maxIngestSequence(),
                sqlTime(observed.lastArrivalAt()), sqlTime(command.arrivedAt()));
    }

    private static ClaimedItem mapClaimedItem(ResultSet rs) throws SQLException {
        return new ClaimedItem(
                rs.getObject("location_event_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getObject("tag_id", UUID.class),
                rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getLong("ingest_sequence"),
                rs.getLong("runtime_revision"),
                rs.getInt("attempts")
        );
    }

    private static Duration boundedBackoff(Duration base, Duration maximum, int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 30);
        Duration calculated;
        try {
            calculated = base.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return maximum;
        }
        return calculated.compareTo(maximum) > 0 ? maximum : calculated;
    }

    private static Timestamp sqlTime(Instant value) {
        return Timestamp.from(value);
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + "必须为非空且长度不超过 " + maxLength);
        }
        return value;
    }

    private static String requireReasonCode(String value) {
        if (value == null || !REASON_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("失败原因必须为固定大写下划线编码");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + "不能为空");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须为正数");
        }
        return value;
    }

    /**
     * 已持久化位置事实的空间评估入队命令。
     *
     * @param locationEventId 位置事实事件标识
     * @param organizationId  客户组织标识
     * @param siteId          矿区标识
     * @param tagId           定位标签标识
     * @param occurredAt      事实发生时间
     * @param ingestSequence  平台摄入序号
     * @param runtimeRevision 定位运行快照修订号
     * @param arrivedAt       进入评估队列的平台时间
     */
    public record EnqueueCommand(
            UUID locationEventId,
            UUID organizationId,
            UUID siteId,
            UUID tagId,
            Instant occurredAt,
            long ingestSequence,
            long runtimeRevision,
            Instant arrivedAt
    ) {

        /**
         * 校验入队命令必填字段和正数顺序字段。
         */
        public EnqueueCommand {
            Objects.requireNonNull(locationEventId, "位置事实事件标识不能为空");
            Objects.requireNonNull(organizationId, "客户组织标识不能为空");
            Objects.requireNonNull(siteId, "矿区标识不能为空");
            Objects.requireNonNull(tagId, "定位标签标识不能为空");
            Objects.requireNonNull(occurredAt, "事实发生时间不能为空");
            Objects.requireNonNull(arrivedAt, "队列到达时间不能为空");
            if (ingestSequence <= 0 || runtimeRevision <= 0) {
                throw new IllegalArgumentException("摄入序号和运行修订必须为正数");
            }
        }
    }

    /**
     * 当前实例已获得数据库租约的空间评估事实。
     *
     * @param locationEventId 位置事实事件标识
     * @param organizationId  客户组织标识
     * @param siteId          矿区标识
     * @param tagId           定位标签标识
     * @param occurredAt      事实发生时间
     * @param ingestSequence  平台摄入序号
     * @param runtimeRevision 定位运行快照修订号
     * @param attempts        包含本次领取的尝试次数
     */
    public record ClaimedItem(
            UUID locationEventId,
            UUID organizationId,
            UUID siteId,
            UUID tagId,
            Instant occurredAt,
            long ingestSequence,
            long runtimeRevision,
            int attempts
    ) {

        private ClaimedItem withAttempts(int updatedAttempts) {
            return new ClaimedItem(
                    locationEventId, organizationId, siteId, tagId,
                    occurredAt, ingestSequence, runtimeRevision, updatedAttempts);
        }
    }
}
