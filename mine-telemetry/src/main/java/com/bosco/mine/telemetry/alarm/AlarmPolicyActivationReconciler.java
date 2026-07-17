package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在告警策略快照激活事务中恢复并移除已经失效的旧规则运行状态。
 */
public final class AlarmPolicyActivationReconciler {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final AlarmOutboxWriter outboxWriter;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建使用系统 UTC 时间和 UUIDv7 的激活协调器。
     *
     * @param dataSource Telemetry 数据源
     */
    public AlarmPolicyActivationReconciler(DataSource dataSource) {
        this(dataSource, Clock.systemUTC(), IdGenerator.timeOrdered());
    }

    /**
     * 创建可测试时间与标识来源的激活协调器。
     *
     * @param dataSource Telemetry 数据源
     * @param clock 平台事实时钟
     * @param idGenerator Outbox 事件标识生成器
     */
    public AlarmPolicyActivationReconciler(
            DataSource dataSource,
            Clock clock,
            IdGenerator idGenerator
    ) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(
                dataSource, "告警激活数据源不能为空"));
        this.clock = Objects.requireNonNull(clock, "告警激活时钟不能为空");
        this.outboxWriter = new AlarmOutboxWriter(idGenerator);
    }

    /**
     * 对暂存快照涉及的设备检查删除、禁用和规则语义变化。
     *
     * @param snapshotId 正在激活的快照标识
     * @param runtimeRevision 正在激活的运行修订号
     * @return 同事务新建的恢复 Outbox 事件标识
     */
    public List<UUID> reconcile(UUID snapshotId, long runtimeRevision) {
        lockAffectedPolicies(snapshotId);
        List<StagedState> states = jdbcTemplate.query("""
                SELECT state.device_id, state.product_key, state.rule_key, state.rule_name,
                       state.source_type, state.severity, state.property_id, state.state,
                       state.alarm_occurrence_id, state.raised_at,
                       state.last_observation::TEXT AS last_observation,
                       state.policy_runtime_revision, state.policy_snapshot_id,
                       state.policy_content_hash, state.rule_fingerprint,
                       stage.values_json::TEXT AS staged_values,
                       stage.content_hash AS staged_content_hash
                  FROM telemetry_alarm_runtime_state state
                  JOIN telemetry_strategy_snapshot_stage stage
                    ON stage.device_id = state.device_id
                 WHERE stage.snapshot_id = ?
                 ORDER BY state.device_id, state.rule_key
                 FOR UPDATE OF state
                """, (resultSet, rowNumber) -> new StagedState(
                        resultSet.getObject("device_id", UUID.class),
                        resultSet.getString("product_key"),
                        resultSet.getString("rule_key"),
                        resultSet.getString("rule_name"),
                        resultSet.getString("source_type"),
                        resultSet.getString("severity"),
                        resultSet.getString("property_id"),
                        AlarmStateMachine.Status.valueOf(resultSet.getString("state")),
                        resultSet.getObject("alarm_occurrence_id", UUID.class),
                        resultSet.getObject("raised_at", java.time.OffsetDateTime.class) == null
                                ? null : resultSet.getObject(
                                        "raised_at", java.time.OffsetDateTime.class).toInstant(),
                        resultSet.getString("last_observation"),
                        resultSet.getLong("policy_runtime_revision"),
                        resultSet.getObject("policy_snapshot_id", UUID.class),
                        resultSet.getString("policy_content_hash"),
                        resultSet.getString("rule_fingerprint"),
                        resultSet.getString("staged_values"),
                        resultSet.getString("staged_content_hash")), snapshotId);
        if (states.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<UUID> eventIds = new ArrayList<>();
        Map<UUID, StagedRules> stagedRulesByDevice = new HashMap<>();
        for (StagedState state : states) {
            StagedRules stagedRules = stagedRulesByDevice.computeIfAbsent(
                    state.deviceId(), ignored -> compileStagedRules(
                            state, snapshotId, runtimeRevision));
            String reason = recoveryReason(state, stagedRules);
            if (reason == null) {
                continue;
            }
            if (state.status() == AlarmStateMachine.Status.ACTIVE
                    || state.status() == AlarmStateMachine.Status.RECOVERING_PENDING) {
                Instant recoveredAt = now.isBefore(state.raisedAt())
                        ? state.raisedAt() : now;
                AlarmRecoveredPayload payload = new AlarmRecoveredPayload(
                        state.occurrenceId(), state.deviceId(), state.productKey(),
                        state.ruleKey(), state.ruleName(), state.sourceType(), state.severity(),
                        state.propertyId(), readEvidence(state.lastObservation()),
                        state.policyRuntimeRevision(), state.policySnapshotId(),
                        state.policyContentHash(), state.raisedAt(), recoveredAt, reason);
                eventIds.add(insertOutbox(
                        snapshotId, state.deviceId(), state.occurrenceId(), recoveredAt, payload));
            }
            jdbcTemplate.update("""
                    DELETE FROM telemetry_alarm_runtime_state
                     WHERE device_id = ? AND rule_key = ?
                    """, state.deviceId(), state.ruleKey());
        }
        return List.copyOf(eventIds);
    }

    private void lockAffectedPolicies(UUID snapshotId) {
        List<UUID> deviceIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT device_id
                  FROM telemetry_strategy_snapshot_stage
                 WHERE snapshot_id = ?
                 ORDER BY device_id
                """, UUID.class, snapshotId);
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            for (UUID deviceId : deviceIds) {
                AlarmPolicyAdvisoryLock.lockExclusive(connection, deviceId);
            }
            return null;
        });
        jdbcTemplate.query("""
                SELECT policy.device_id
                  FROM telemetry_alarm_policy policy
                  JOIN telemetry_strategy_snapshot_stage stage
                    ON stage.device_id = policy.device_id
                 WHERE stage.snapshot_id = ?
                 ORDER BY policy.device_id
                 FOR UPDATE OF policy
                """, resultSet -> { }, snapshotId);
    }

    private String recoveryReason(
            StagedState state,
            StagedRules stagedRules
    ) {
        if (stagedRules.removed()) {
            return "RULE_REMOVED";
        }
        AlarmPolicy.Rule replacement = stagedRules.rules().get(state.ruleKey());
        if (replacement == null) {
            return "RULE_REMOVED";
        }
        if (!replacement.enabled()) {
            return "RULE_DISABLED";
        }
        return AlarmRuleFingerprint.sha256(replacement).equals(state.ruleFingerprint())
                ? null : "RULE_CHANGED";
    }

    private StagedRules compileStagedRules(
            StagedState state,
            UUID snapshotId,
            long runtimeRevision
    ) {
        if ("{}".equals(state.stagedValues())) {
            return new StagedRules(true, Map.of());
        }
        AlarmPolicy policy;
        try {
            policy = AlarmPolicy.from(
                    snapshotId, runtimeRevision, state.stagedContentHash(),
                    jsonMapper.readValue(state.stagedValues(), MAP_TYPE));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("暂存告警策略无法安全协调旧运行状态", exception);
        }
        Map<String, AlarmPolicy.Rule> rules = new LinkedHashMap<>();
        for (AlarmPolicy.Rule rule : policy.rules()) {
            rules.put(rule.key(), rule);
        }
        return new StagedRules(false, Map.copyOf(rules));
    }

    private UUID insertOutbox(
            UUID traceId,
            UUID deviceId,
            UUID occurrenceId,
            Instant recoveredAt,
            AlarmRecoveredPayload payload
    ) {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) connection ->
                outboxWriter.insert(
                        connection, traceId, deviceId, occurrenceId,
                        "alarm.recovered", recoveredAt, payload));
    }

    private AlarmEvidencePayload readEvidence(String json) {
        try {
            return jsonMapper.readValue(json, AlarmEvidencePayload.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("旧告警状态证据无法解析", exception);
        }
    }

    private record StagedState(
            UUID deviceId,
            String productKey,
            String ruleKey,
            String ruleName,
            String sourceType,
            String severity,
            String propertyId,
            AlarmStateMachine.Status status,
            UUID occurrenceId,
            Instant raisedAt,
            String lastObservation,
            long policyRuntimeRevision,
            UUID policySnapshotId,
            String policyContentHash,
            String ruleFingerprint,
            String stagedValues,
            String stagedContentHash
    ) {
    }

    private record StagedRules(
            boolean removed,
            Map<String, AlarmPolicy.Rule> rules
    ) {
    }
}
