package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.messaging.strategy.StrategyRuntimeChangedPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 在 TimescaleDB 中暂存策略快照并原子切换单设备数据质量策略镜像。
 */
public class StrategyPolicyRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建策略持久镜像仓储。
     *
     * @param dataSource Telemetry 时序数据库连接池
     */
    public StrategyPolicyRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "数据源不能为空");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** 查询消息是否已经完成幂等处理。 */
    public boolean isEventProcessed(UUID eventId) {
        Boolean processed = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM telemetry_strategy_event_inbox WHERE event_id = ?
                )
                """, Boolean.class, eventId);
        return Boolean.TRUE.equals(processed);
    }

    /**
     * 幂等暂存一页已经独立校验的设备策略。
     *
     * @param snapshotId 快照标识
     * @param policies 本页策略
     */
    public void stage(UUID snapshotId, List<StagedPolicy> policies) {
        if (policies.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO telemetry_strategy_snapshot_stage (
                    snapshot_id, device_id, values_json, content_hash, staged_at
                ) VALUES (?, ?, CAST(? AS JSONB), ?, CURRENT_TIMESTAMP)
                ON CONFLICT (snapshot_id, device_id) DO UPDATE SET
                    values_json = EXCLUDED.values_json,
                    content_hash = EXCLUDED.content_hash,
                    staged_at = CURRENT_TIMESTAMP
                """, policies, policies.size(), (PreparedStatement statement, StagedPolicy policy) -> {
                    statement.setObject(1, snapshotId);
                    statement.setObject(2, policy.deviceId());
                    statement.setString(3, policy.valuesJson());
                    statement.setString(4, policy.contentHash());
                });
    }

    /** 删除未通过完整性校验的暂存快照。 */
    public void discard(UUID snapshotId) {
        jdbcTemplate.update(
                "DELETE FROM telemetry_strategy_snapshot_stage WHERE snapshot_id = ?",
                snapshotId
        );
    }

    /**
     * 在单个数据库事务中校验修订水位、应用增量、推进水位并记录 Inbox。
     *
     * @param eventId 消息事件标识
     * @param payload 已完成整包校验的轻量事件
     */
    public void finalizeApply(UUID eventId, StrategyRuntimeChangedPayload payload) {
        transactionTemplate.executeWithoutResult(status -> {
            lockProductStrategy(payload.productId(), payload.strategyType());
            CurrentRevision current = currentRevision(payload.productId(), payload.strategyType());
            if (current != null && current.runtimeRevision() >= payload.runtimeRevision()) {
                if (current.runtimeRevision() == payload.runtimeRevision()
                        && (!current.snapshotId().equals(payload.snapshotId())
                        || current.itemCount() != payload.itemCount()
                        || !current.contentHash().equals(payload.contentHash()))) {
                    throw new PermanentStrategySnapshotException("相同运行修订号对应了不同快照声明");
                }
                recordInbox(eventId, payload.snapshotId());
                discard(payload.snapshotId());
                return;
            }
            Integer stagedCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                      FROM telemetry_strategy_snapshot_stage
                     WHERE snapshot_id = ?
                    """, Integer.class, payload.snapshotId());
            if (stagedCount == null || stagedCount != payload.itemCount()) {
                throw new PermanentStrategySnapshotException("暂存策略条目数与事件声明不一致");
            }

            jdbcTemplate.update("""
                    DELETE FROM telemetry_data_quality_policy policy
                    USING telemetry_strategy_snapshot_stage stage
                    WHERE stage.snapshot_id = ?
                      AND stage.device_id = policy.device_id
                      AND stage.values_json = '{}'::JSONB
                    """, payload.snapshotId());
            jdbcTemplate.update("""
                    INSERT INTO telemetry_data_quality_policy (
                        device_id, product_id, runtime_revision, snapshot_id,
                        values_json, content_hash, updated_at
                    )
                    SELECT device_id, ?, ?, ?, values_json, content_hash, CURRENT_TIMESTAMP
                      FROM telemetry_strategy_snapshot_stage
                     WHERE snapshot_id = ?
                       AND values_json <> '{}'::JSONB
                    ON CONFLICT (device_id) DO UPDATE SET
                        product_id = EXCLUDED.product_id,
                        runtime_revision = EXCLUDED.runtime_revision,
                        snapshot_id = EXCLUDED.snapshot_id,
                        values_json = EXCLUDED.values_json,
                        content_hash = EXCLUDED.content_hash,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE telemetry_data_quality_policy.runtime_revision < EXCLUDED.runtime_revision
                    """, payload.productId(), payload.runtimeRevision(), payload.snapshotId(),
                    payload.snapshotId());
            jdbcTemplate.update("""
                    INSERT INTO telemetry_strategy_runtime_revision (
                        product_id, strategy_type, runtime_revision, snapshot_id,
                        item_count, content_hash, applied_at
                    ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (product_id, strategy_type) DO UPDATE SET
                        runtime_revision = EXCLUDED.runtime_revision,
                        snapshot_id = EXCLUDED.snapshot_id,
                        item_count = EXCLUDED.item_count,
                        content_hash = EXCLUDED.content_hash,
                        applied_at = CURRENT_TIMESTAMP
                    WHERE telemetry_strategy_runtime_revision.runtime_revision
                          < EXCLUDED.runtime_revision
                    """, payload.productId(), payload.strategyType(), payload.runtimeRevision(),
                    payload.snapshotId(), payload.itemCount(), payload.contentHash());
            recordInbox(eventId, payload.snapshotId());
            discard(payload.snapshotId());
        });
    }

    private void lockProductStrategy(UUID productId, String strategyType) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> { }, productId + ":" + strategyType
        );
    }

    private CurrentRevision currentRevision(UUID productId, String strategyType) {
        List<CurrentRevision> rows = jdbcTemplate.query("""
                SELECT runtime_revision, snapshot_id, item_count, content_hash
                  FROM telemetry_strategy_runtime_revision
                 WHERE product_id = ?
                   AND strategy_type = ?
                """, (resultSet, rowNumber) -> new CurrentRevision(
                        resultSet.getLong("runtime_revision"),
                        resultSet.getObject("snapshot_id", UUID.class),
                        resultSet.getInt("item_count"),
                        resultSet.getString("content_hash")
                ), productId, strategyType);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void recordInbox(UUID eventId, UUID snapshotId) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_strategy_event_inbox (event_id, snapshot_id)
                VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, snapshotId);
    }

    /** 已独立校验并规范化的暂存策略。 */
    public record StagedPolicy(UUID deviceId, String valuesJson, String contentHash) {
    }

    private record CurrentRevision(
            long runtimeRevision,
            UUID snapshotId,
            int itemCount,
            String contentHash
    ) {
    }
}
