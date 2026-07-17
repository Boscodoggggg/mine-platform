package com.bosco.mine.server.modules.aggregation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 持久化显式聚合重算请求事实，并提供按操作人隔离的幂等查询。
 */
@Repository
public class AggregationRecomputeRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建聚合重算请求仓储。
     *
     * @param jdbcTemplate 业务库访问模板
     */
    public AggregationRecomputeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按操作人和幂等键查询既有请求。
     *
     * @param requestedBy 操作人标识
     * @param idempotencyKey 幂等键
     * @return 既有请求事实
     */
    public Optional<RequestRecord> findByIdempotency(UUID requestedBy, String idempotencyKey) {
        return jdbcTemplate.query("""
                SELECT request_id, device_id, property_id, bucket_seconds,
                       from_at, to_at, reason, idempotency_key, requested_by,
                       status, created_at, completed_at, execution_id, last_error
                  FROM dev_aggregation_recompute_request
                 WHERE requested_by = ?
                   AND idempotency_key = ?
                """, AggregationRecomputeRepository::mapRecord, requestedBy, idempotencyKey)
                .stream()
                .findFirst();
    }

    /**
     * 插入待执行请求；并发幂等冲突时不覆盖既有事实。
     *
     * @param record 待保存请求
     * @return 是否成功插入
     */
    public boolean insert(RequestRecord record) {
        return jdbcTemplate.update("""
                INSERT INTO dev_aggregation_recompute_request (
                    request_id, device_id, property_id, bucket_seconds,
                    from_at, to_at, reason, idempotency_key, requested_by,
                    status, created_at, completed_at, execution_id, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (requested_by, idempotency_key) DO NOTHING
                """,
                record.requestId(), record.deviceId(), record.propertyId(),
                record.bucketSeconds(), Timestamp.from(record.from()), Timestamp.from(record.to()),
                record.reason(), record.idempotencyKey(), record.requestedBy(), record.status(),
                Timestamp.from(record.createdAt()), nullableTimestamp(record.completedAt()),
                record.executionId(), record.lastError()) == 1;
    }

    /**
     * 查询最近创建的重算请求事实。
     *
     * @param limit 最大返回数量
     * @return 按创建时间倒序排列的请求
     */
    public List<RequestRecord> findRecent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("聚合重算请求数量必须在 1 到 100 之间");
        }
        return jdbcTemplate.query("""
                SELECT request_id, device_id, property_id, bucket_seconds,
                       from_at, to_at, reason, idempotency_key, requested_by,
                       status, created_at, completed_at, execution_id, last_error
                  FROM dev_aggregation_recompute_request
                 ORDER BY created_at DESC, request_id DESC
                 LIMIT ?
                """, AggregationRecomputeRepository::mapRecord, limit);
    }

    private static RequestRecord mapRecord(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        Timestamp completedAt = resultSet.getTimestamp("completed_at");
        return new RequestRecord(
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("device_id", UUID.class),
                resultSet.getString("property_id"),
                resultSet.getInt("bucket_seconds"),
                resultSet.getTimestamp("from_at").toInstant(),
                resultSet.getTimestamp("to_at").toInstant(),
                resultSet.getString("reason"),
                resultSet.getString("idempotency_key"),
                resultSet.getObject("requested_by", UUID.class),
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                resultSet.getObject("execution_id", UUID.class),
                resultSet.getString("last_error")
        );
    }

    private static Timestamp nullableTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /**
     * 聚合重算请求持久化快照。
     *
     * @param requestId 请求标识
     * @param deviceId 设备标识
     * @param propertyId 属性标识
     * @param bucketSeconds 桶宽秒数
     * @param from 开始时间
     * @param to 结束时间
     * @param reason 重算原因
     * @param idempotencyKey 幂等键
     * @param requestedBy 申请人标识
     * @param status 请求状态
     * @param createdAt 创建时间
     * @param completedAt 完成时间
     * @param executionId Telemetry 执行标识
     * @param lastError 脱敏错误摘要
     */
    public record RequestRecord(
            UUID requestId,
            UUID deviceId,
            String propertyId,
            int bucketSeconds,
            Instant from,
            Instant to,
            String reason,
            String idempotencyKey,
            UUID requestedBy,
            String status,
            Instant createdAt,
            Instant completedAt,
            UUID executionId,
            String lastError
    ) {
    }
}
