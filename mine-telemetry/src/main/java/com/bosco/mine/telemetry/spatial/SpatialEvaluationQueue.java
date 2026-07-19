package com.bosco.mine.telemetry.spatial;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final PolicyResolver policyResolver;
    private final SpatialEventMetrics metrics;

    /**
     * 创建使用本地数据库事务和 PostgreSQL 行锁的空间评估队列。
     *
     * @param dataSource      Telemetry 数据源
     * @param watermarkPolicy 事件时间水位策略
     */
    public SpatialEvaluationQueue(DataSource dataSource, SpatialWatermarkPolicy watermarkPolicy) {
        this(dataSource, fixedPolicy(watermarkPolicy), SpatialEventMetrics.noop());
    }

    /**
     * 创建带空间运行指标的固定水位策略队列。
     *
     * @param dataSource Telemetry 数据源
     * @param watermarkPolicy 事件时间水位策略
     * @param metrics 空间事件运行指标
     */
    public SpatialEvaluationQueue(
            DataSource dataSource,
            SpatialWatermarkPolicy watermarkPolicy,
            SpatialEventMetrics metrics
    ) {
        this(dataSource, fixedPolicy(watermarkPolicy), metrics);
    }

    /**
     * 创建使用精确空间运行目录解析发布版本迟到策略的持久队列。
     *
     * @param dataSource Telemetry 数据源
     * @param runtimeCatalog 入队时原子固化精确空间修订的运行目录
     */
    public SpatialEvaluationQueue(
            DataSource dataSource,
            SpatialRuntimeCatalog runtimeCatalog
    ) {
        this(dataSource, Objects.requireNonNull(
                runtimeCatalog, "空间运行目录不能为空")::capture,
                SpatialEventMetrics.noop());
    }

    /**
     * 创建带空间运行指标并按发布版本解析策略的持久队列。
     *
     * @param dataSource Telemetry 数据源
     * @param runtimeCatalog 空间运行目录
     * @param metrics 空间事件运行指标
     */
    public SpatialEvaluationQueue(
            DataSource dataSource,
            SpatialRuntimeCatalog runtimeCatalog,
            SpatialEventMetrics metrics
    ) {
        this(dataSource, Objects.requireNonNull(
                runtimeCatalog, "空间运行目录不能为空")::capture, metrics);
    }

    /**
     * 创建使用可替换策略解析器的持久空间评估队列。
     *
     * @param dataSource Telemetry 数据源
     * @param policyResolver 入队命令对应的精确运行策略解析器
     */
    public SpatialEvaluationQueue(DataSource dataSource, PolicyResolver policyResolver) {
        this(dataSource, policyResolver, SpatialEventMetrics.noop());
    }

    /**
     * 创建使用可替换策略解析器并记录运行指标的持久队列。
     *
     * @param dataSource Telemetry 数据源
     * @param policyResolver 入队命令对应的精确运行策略解析器
     * @param metrics 空间事件运行指标
     */
    public SpatialEvaluationQueue(
            DataSource dataSource,
            PolicyResolver policyResolver,
            SpatialEventMetrics metrics
    ) {
        Objects.requireNonNull(dataSource, "数据源不能为空");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.policyResolver = Objects.requireNonNull(policyResolver, "水位策略解析器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "空间事件运行指标不能为空");
    }

    /**
     * 把已经持久化的位置事实幂等加入空间评估队列并推进标签水位。
     *
     * @param command 入队命令
     * @return 首次入队返回 {@code true}，重复事件返回 {@code false}
     */
    public boolean enqueue(EnqueueCommand command) {
        Objects.requireNonNull(command, "入队命令不能为空");
        EnqueueOutcome outcome = transactions.execute(status -> {
            Optional<ResolvedPolicy> resolved = policyResolver.resolve(command);
            if (resolved.isEmpty()) {
                return EnqueueOutcome.NO_ACTIVE_ZONE;
            }
            ResolvedPolicy policy = resolved.get();
            lockSubject(command.siteId(), command.tagId());
            Optional<SpatialWatermarkPolicy.Watermark> previous = findWatermark(
                    command.siteId(), command.tagId());
            boolean tooLate = previous
                    .map(watermark -> policy.watermarkPolicy().isTooLate(
                            watermark, command.occurredAt(), command.ingestSequence()))
                    .orElse(false);
            int changed = jdbc.update("""
                    INSERT INTO spatial_evaluation_queue (
                        location_event_id, organization_id, site_id, tag_id,
                        occurred_at, ingest_sequence, runtime_revision,
                        allowed_lateness_millis, idle_flush_millis,
                        status, result_reason, next_attempt_at, enqueued_at, processed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (location_event_id) DO NOTHING
                    """,
                    command.locationEventId(), command.organizationId(), command.siteId(),
                    command.tagId(), sqlTime(command.occurredAt()), command.ingestSequence(),
                    policy.runtimeRevision(), policy.watermarkPolicy().allowedLateness().toMillis(),
                    policy.watermarkPolicy().idleFlushAfter().toMillis(),
                    tooLate ? "SKIPPED" : "PENDING",
                    tooLate ? "TOO_LATE" : null, sqlTime(command.arrivedAt()),
                    sqlTime(command.arrivedAt()), tooLate ? sqlTime(command.arrivedAt()) : null);
            if (changed == 0) {
                return EnqueueOutcome.DUPLICATE;
            }
            SpatialWatermarkPolicy.Watermark observed = previous
                    .map(current -> policy.watermarkPolicy().observe(
                            current, command.occurredAt(), command.ingestSequence(), command.arrivedAt()))
                    .orElseGet(() -> new SpatialWatermarkPolicy.Watermark(
                            command.occurredAt(), command.ingestSequence(), command.arrivedAt(),
                            null, null));
            upsertWatermark(command, observed);
            return tooLate ? EnqueueOutcome.TOO_LATE : EnqueueOutcome.SUCCESS;
        });
        EnqueueOutcome committed = Objects.requireNonNull(outcome, "空间评估入队事务结果不能为空");
        afterCommit(() -> metrics.queue(committed.result, committed.reason));
        return committed.inserted;
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
                           q.occurred_at, q.ingest_sequence, q.runtime_revision, q.attempts,
                           ordering_policy.allowed_lateness_millis,
                           ordering_policy.idle_flush_millis
                    FROM spatial_evaluation_queue q
                    JOIN spatial_subject_watermark watermark
                      ON watermark.site_id = q.site_id AND watermark.tag_id = q.tag_id
                    JOIN LATERAL (
                        SELECT MAX(policy.allowed_lateness_millis)
                                   AS allowed_lateness_millis,
                               MAX(policy.idle_flush_millis) AS idle_flush_millis
                        FROM spatial_evaluation_queue policy
                        WHERE policy.site_id = q.site_id AND policy.tag_id = q.tag_id
                          AND policy.status IN ('PENDING', 'PROCESSING', 'FAILED')
                    ) ordering_policy ON ordering_policy.allowed_lateness_millis IS NOT NULL
                    WHERE q.status IN ('PENDING', 'FAILED')
                      AND q.next_attempt_at <= ?
                      AND q.occurred_at <= CASE
                          WHEN ? >= watermark.last_arrival_at
                              + (ordering_policy.idle_flush_millis
                                 * INTERVAL '1 millisecond')
                          THEN watermark.max_occurred_at
                          ELSE watermark.max_occurred_at
                              - (ordering_policy.allowed_lateness_millis
                                 * INTERVAL '1 millisecond')
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
                    FOR UPDATE OF q, watermark SKIP LOCKED
                    LIMIT ?
                    """, (rs, rowNum) -> mapClaimedItem(rs),
                    sqlTime(now), sqlTime(now), limit);
            Instant leaseExpiresAt = now.plus(safeLease);
            List<ClaimedItem> claimed = new ArrayList<>(candidates.size());
            for (ClaimedItem candidate : candidates) {
                UUID leaseToken = UUID.randomUUID();
                int changed = jdbc.update("""
                        UPDATE spatial_evaluation_queue
                        SET status = 'PROCESSING', result_reason = NULL, attempts = attempts + 1,
                            lease_token = ?, locked_by = ?, locked_at = ?, lease_expires_at = ?
                        WHERE location_event_id = ?
                          AND status IN ('PENDING', 'FAILED')
                        """, leaseToken, safeWorkerId, sqlTime(now), sqlTime(leaseExpiresAt),
                        candidate.locationEventId());
                if (changed == 1) {
                    finalizeWatermark(candidate, now);
                    claimed.add(candidate.withLease(leaseToken, candidate.attempts() + 1));
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
     * @param leaseToken      本次领取返回的防旧执行令牌
     * @param processedAt     完成时间
     */
    public void markProcessed(
            UUID locationEventId,
            String workerId,
            UUID leaseToken,
            Instant processedAt
    ) {
        Objects.requireNonNull(locationEventId, "位置事实事件标识不能为空");
        String safeWorkerId = requireText(workerId, "处理实例标识", 128);
        Objects.requireNonNull(leaseToken, "租约令牌不能为空");
        Objects.requireNonNull(processedAt, "完成时间不能为空");
        int changed = jdbc.update("""
                UPDATE spatial_evaluation_queue
                SET status = 'PROCESSED', result_reason = NULL, processed_at = ?,
                    lease_token = NULL, locked_by = NULL, locked_at = NULL,
                    lease_expires_at = NULL
                WHERE location_event_id = ? AND status = 'PROCESSING'
                  AND locked_by = ? AND lease_token = ? AND lease_expires_at > ?
                """, sqlTime(processedAt), locationEventId, safeWorkerId,
                leaseToken, sqlTime(processedAt));
        requireChanged(changed, "空间评估记录不属于当前实例或已离开处理中状态");
        afterCommit(() -> metrics.stage(SpatialEventMetrics.Stage.EVALUATION,
                SpatialEventMetrics.Result.SUCCESS, SpatialEventMetrics.Reason.NONE));
    }

    /**
     * 记录当前处理失败，并按尝试次数退避或进入死信状态。
     *
     * @param locationEventId 位置事实事件标识
     * @param workerId        当前实例标识
     * @param leaseToken      本次领取返回的防旧执行令牌
     * @param failedAt        失败时间
     * @param baseBackoff     首次失败退避时长
     * @param maxBackoff      最大退避时长
     * @param maxAttempts     最大处理尝试次数
     * @param reasonCode      固定低基数失败原因
     */
    public void markFailed(
            UUID locationEventId,
            String workerId,
            UUID leaseToken,
            Instant failedAt,
            Duration baseBackoff,
            Duration maxBackoff,
            int maxAttempts,
            String reasonCode
    ) {
        Objects.requireNonNull(locationEventId, "位置事实事件标识不能为空");
        String safeWorkerId = requireText(workerId, "处理实例标识", 128);
        Objects.requireNonNull(leaseToken, "租约令牌不能为空");
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
        Boolean dead = transactions.execute(status -> {
            Integer attempts = jdbc.query("""
                    SELECT attempts
                    FROM spatial_evaluation_queue
                    WHERE location_event_id = ? AND status = 'PROCESSING'
                      AND locked_by = ? AND lease_token = ? AND lease_expires_at > ?
                    FOR UPDATE
                    """, rs -> rs.next() ? rs.getInt(1) : null,
                    locationEventId, safeWorkerId, leaseToken, sqlTime(failedAt));
            if (attempts == null) {
                throw new IllegalStateException("空间评估记录不属于当前实例或已离开处理中状态");
            }
            boolean isDead = attempts >= maxAttempts;
            Duration backoff = boundedBackoff(safeBase, safeMax, attempts);
            int changed = jdbc.update("""
                    UPDATE spatial_evaluation_queue
                    SET status = ?, result_reason = ?, next_attempt_at = ?, processed_at = ?,
                        lease_token = NULL, locked_by = NULL, locked_at = NULL,
                        lease_expires_at = NULL
                    WHERE location_event_id = ? AND status = 'PROCESSING'
                      AND locked_by = ? AND lease_token = ? AND lease_expires_at > ?
                    """, isDead ? "DEAD" : "FAILED", safeReason,
                    sqlTime(failedAt.plus(backoff)), isDead ? sqlTime(failedAt) : null,
                    locationEventId, safeWorkerId, leaseToken, sqlTime(failedAt));
            requireChanged(changed, "空间评估失败状态更新发生并发冲突");
            return isDead;
        });
        afterCommit(() -> metrics.stage(SpatialEventMetrics.Stage.EVALUATION,
                Boolean.TRUE.equals(dead)
                    ? SpatialEventMetrics.Result.DEAD
                    : SpatialEventMetrics.Result.FAILURE,
                SpatialEventMetrics.Reason.STATE_RECOVERY));
    }

    /** 空间事实入队事务的固定结果。 */
    private enum EnqueueOutcome {
        /** 正常进入等待队列。 */
        SUCCESS(true, SpatialEventMetrics.Result.SUCCESS, SpatialEventMetrics.Reason.NONE),
        /** 事件已存在。 */
        DUPLICATE(false, SpatialEventMetrics.Result.SKIPPED, SpatialEventMetrics.Reason.DUPLICATE),
        /** 当前没有生效区域。 */
        NO_ACTIVE_ZONE(false, SpatialEventMetrics.Result.SKIPPED,
                SpatialEventMetrics.Reason.NO_ACTIVE_ZONE),
        /** 事件晚于已完成水位，记录审计但不再评估。 */
        TOO_LATE(true, SpatialEventMetrics.Result.SKIPPED, SpatialEventMetrics.Reason.TOO_LATE);

        private final boolean inserted;
        private final SpatialEventMetrics.Result result;
        private final SpatialEventMetrics.Reason reason;

        EnqueueOutcome(
                boolean inserted,
                SpatialEventMetrics.Result result,
                SpatialEventMetrics.Reason reason
        ) {
            this.inserted = inserted;
            this.result = result;
            this.reason = reason;
        }
    }

    private void recoverExpiredLeases(Instant now) {
        jdbc.update("""
                UPDATE spatial_evaluation_queue
                SET status = 'FAILED', result_reason = 'LEASE_EXPIRED', next_attempt_at = ?,
                    lease_token = NULL, locked_by = NULL, locked_at = NULL,
                    lease_expires_at = NULL
                WHERE status = 'PROCESSING' AND lease_expires_at <= ?
                """, sqlTime(now), sqlTime(now));
    }

    /**
     * 当前线程存在外层事务时延迟到提交成功后执行，否则立即执行。
     *
     * @param action 只允许执行低风险内存指标更新的动作
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
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
                SELECT max_occurred_at, max_ingest_sequence, last_arrival_at,
                       finalized_occurred_at, finalized_ingest_sequence
                FROM spatial_subject_watermark
                WHERE site_id = ? AND tag_id = ?
                FOR UPDATE
                """, rs -> rs.next()
                ? Optional.of(new SpatialWatermarkPolicy.Watermark(
                        rs.getObject("max_occurred_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getLong("max_ingest_sequence"),
                        rs.getObject("last_arrival_at", java.time.OffsetDateTime.class).toInstant(),
                        instantOrNull(rs, "finalized_occurred_at"),
                        longOrNull(rs, "finalized_ingest_sequence")))
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
                    updated_at = GREATEST(
                        spatial_subject_watermark.updated_at,
                        EXCLUDED.updated_at
                    )
                """, command.organizationId(), command.siteId(), command.tagId(),
                sqlTime(observed.maxOccurredAt()), observed.maxIngestSequence(),
                sqlTime(observed.lastArrivalAt()), sqlTime(command.arrivedAt()));
    }

    private void finalizeWatermark(ClaimedItem item, Instant now) {
        int changed = jdbc.update("""
                UPDATE spatial_subject_watermark
                SET finalized_occurred_at = ?, finalized_ingest_sequence = ?,
                    last_flushed_at = CASE
                        WHEN ? >= last_arrival_at + (? * INTERVAL '1 millisecond')
                        THEN ? ELSE last_flushed_at
                    END,
                    updated_at = GREATEST(updated_at, ?)
                WHERE site_id = ? AND tag_id = ?
                  AND (
                    finalized_occurred_at IS NULL
                    OR (finalized_occurred_at, finalized_ingest_sequence) <= (?, ?)
                  )
                """, sqlTime(item.occurredAt()), item.ingestSequence(), sqlTime(now),
                item.idleFlushMillis(), sqlTime(now), sqlTime(now),
                item.siteId(), item.tagId(), sqlTime(item.occurredAt()), item.ingestSequence());
        requireChanged(changed, "空间事件时间前沿未能随领取记录推进");
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
                rs.getInt("attempts"),
                rs.getLong("allowed_lateness_millis"),
                rs.getLong("idle_flush_millis"),
                null
        );
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
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

    private static PolicyResolver fixedPolicy(SpatialWatermarkPolicy watermarkPolicy) {
        SpatialWatermarkPolicy safePolicy = Objects.requireNonNull(
                watermarkPolicy, "水位策略不能为空");
        return command -> Optional.of(new ResolvedPolicy(
                safePolicy, command.runtimeRevision()));
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
     * @param allowedLatenessMillis 入队时固化的允许迟到毫秒数
     * @param idleFlushMillis 入队时固化的空闲刷新毫秒数
     * @param leaseToken      本次领取唯一且防止旧执行完成新租约的令牌
     */
    public record ClaimedItem(
            UUID locationEventId,
            UUID organizationId,
            UUID siteId,
            UUID tagId,
            Instant occurredAt,
            long ingestSequence,
            long runtimeRevision,
            int attempts,
            long allowedLatenessMillis,
            long idleFlushMillis,
            UUID leaseToken
    ) {

        private ClaimedItem withLease(UUID updatedLeaseToken, int updatedAttempts) {
            return new ClaimedItem(
                    locationEventId, organizationId, siteId, tagId,
                    occurredAt, ingestSequence, runtimeRevision,
                    updatedAttempts, allowedLatenessMillis, idleFlushMillis,
                    updatedLeaseToken);
        }
    }

    /** 入队时解析发布区域策略并固化运行修订的函数。 */
    @FunctionalInterface
    public interface PolicyResolver {
        /**
         * @param command 已解析的位置事实命令
         * @return 无生效区域时返回空，否则返回精确修订与水位策略
         */
        Optional<ResolvedPolicy> resolve(EnqueueCommand command);
    }

    /**
     * 入队位置事实应使用的精确运行修订和事件时间水位策略。
     *
     * @param watermarkPolicy 发布区域合并后的保守水位策略
     * @param runtimeRevision 已固化的精确空间运行修订号
     */
    public record ResolvedPolicy(
            SpatialWatermarkPolicy watermarkPolicy,
            long runtimeRevision
    ) {
        /** 校验运行策略完整且修订号为正数。 */
        public ResolvedPolicy {
            Objects.requireNonNull(watermarkPolicy, "空间水位策略不能为空");
            if (runtimeRevision < 1) {
                throw new IllegalArgumentException("空间运行修订号必须为正数");
            }
        }
    }
}
