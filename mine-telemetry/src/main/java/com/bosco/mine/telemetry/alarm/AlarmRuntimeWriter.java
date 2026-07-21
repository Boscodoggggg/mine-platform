package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;
import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import com.bosco.mine.telemetry.quality.QualityEvidence;
import com.bosco.mine.telemetry.quality.QualityEvaluationCoverage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在调用方遥测事务中锁定并推进单设备告警状态，同时写入可靠 Outbox 事件。
 */
public final class AlarmRuntimeWriter {

    private static final String FIND_STATE_SQL = """
            SELECT product_key, rule_name, source_type, severity, property_id, state,
                   alarm_occurrence_id, candidate_since, raised_at, recovered_at,
                   cooldown_until, last_received_at, last_observation::TEXT,
                   policy_runtime_revision, policy_snapshot_id, policy_content_hash,
                   rule_fingerprint, version
              FROM telemetry_alarm_runtime_state
             WHERE device_id = ? AND rule_key = ?
             FOR UPDATE
            """;
    private static final String DELETE_STATE_SQL = """
            DELETE FROM telemetry_alarm_runtime_state
             WHERE device_id = ? AND rule_key = ?
            """;
    private static final String UPSERT_STATE_SQL = """
            INSERT INTO telemetry_alarm_runtime_state (
                device_id, product_key, rule_key, rule_name, source_type, severity,
                property_id, state, alarm_occurrence_id, candidate_since, raised_at,
                recovered_at, cooldown_until, last_received_at, last_observation,
                policy_runtime_revision, policy_snapshot_id, policy_content_hash,
                rule_fingerprint, version, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB),
                      ?, ?, ?, ?, 0, CURRENT_TIMESTAMP)
            ON CONFLICT (device_id, rule_key) DO UPDATE SET
                product_key = EXCLUDED.product_key,
                rule_name = EXCLUDED.rule_name,
                source_type = EXCLUDED.source_type,
                severity = EXCLUDED.severity,
                property_id = EXCLUDED.property_id,
                state = EXCLUDED.state,
                alarm_occurrence_id = EXCLUDED.alarm_occurrence_id,
                candidate_since = EXCLUDED.candidate_since,
                raised_at = EXCLUDED.raised_at,
                recovered_at = EXCLUDED.recovered_at,
                cooldown_until = EXCLUDED.cooldown_until,
                last_received_at = EXCLUDED.last_received_at,
                last_observation = EXCLUDED.last_observation,
                policy_runtime_revision = EXCLUDED.policy_runtime_revision,
                policy_snapshot_id = EXCLUDED.policy_snapshot_id,
                policy_content_hash = EXCLUDED.policy_content_hash,
                rule_fingerprint = EXCLUDED.rule_fingerprint,
                version = telemetry_alarm_runtime_state.version + 1,
                updated_at = CURRENT_TIMESTAMP
            """;
    private final AlarmRuleEvaluator evaluator = new AlarmRuleEvaluator();
    private final AlarmStateMachine stateMachine;
    private final AlarmOutboxWriter outboxWriter;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /** 创建使用 UUIDv7 的告警运行写入器。 */
    public AlarmRuntimeWriter() {
        this(IdGenerator.timeOrdered());
    }

    /**
     * 创建可替换标识生成器的告警运行写入器。
     *
     * @param idGenerator 告警周期与 Outbox 事件标识生成器
     */
    public AlarmRuntimeWriter(IdGenerator idGenerator) {
        Objects.requireNonNull(idGenerator, "告警标识生成器不能为空");
        this.stateMachine = new AlarmStateMachine(idGenerator::next);
        this.outboxWriter = new AlarmOutboxWriter(idGenerator);
    }

    /**
     * 对一帧已经成为持久最新值的遥测执行全部启用规则。
     *
     * @param connection 调用方事务连接
     * @param samples 同一遥测事件的属性点
     * @param qualityEvidence 同一事件的质量证据
     * @param policy 设备最后正确告警策略
     * @param qualityCoverage 本帧实际执行到的数据质量规则与属性范围
     * @return 本事务新建的 Outbox 事件标识
     * @throws SQLException 状态或 Outbox 写入失败
     */
    public List<UUID> apply(
            Connection connection,
            List<TelemetrySample> samples,
            List<QualityEvidence> qualityEvidence,
            AlarmPolicy policy,
            QualityEvaluationCoverage qualityCoverage
    ) throws SQLException {
        Objects.requireNonNull(connection, "告警事务连接不能为空");
        List<TelemetrySample> frame = List.copyOf(Objects.requireNonNull(
                samples, "告警遥测帧不能为空"));
        List<QualityEvidence> evidence = List.copyOf(Objects.requireNonNull(
                qualityEvidence, "告警质量证据不能为空"));
        Objects.requireNonNull(policy, "告警策略不能为空");
        Objects.requireNonNull(qualityCoverage, "数据质量评估覆盖范围不能为空");
        if (frame.isEmpty()) {
            throw new IllegalArgumentException("告警遥测帧不能为空");
        }
        TelemetrySample first = frame.getFirst();
        List<UUID> outboxEventIds = new ArrayList<>();
        for (AlarmPolicy.Rule rule : policy.rules()) {
            if (!rule.enabled()) {
                continue;
            }
            String fingerprint = AlarmRuleFingerprint.sha256(rule);
            StoredState stored = findState(connection, first.deviceId(), rule.key());
            if (stored != null && !stored.ruleFingerprint().equals(fingerprint)) {
                appendChangedRecovery(connection, first, stored, outboxEventIds);
                deleteState(connection, first.deviceId(), rule.key());
                stored = null;
            }
            AlarmStateMachine.State previous = stored == null
                    ? AlarmStateMachine.State.normal() : stored.state();
            AlarmEvaluation evaluation = evaluator.evaluate(
                    rule, frame, evidence, qualityCoverage);
            AlarmStateMachine.Transition transition = stateMachine.advance(
                    previous, evaluation, first.receivedAt(), rule);
            String observation = evaluation.evidence() == null
                    ? stored == null ? "{}" : stored.lastObservationJson()
                    : writeJson(evaluation.evidence());
            upsertState(
                    connection, first, policy, rule, fingerprint, stored,
                    transition.state(), observation);
            if (transition.event() != null) {
                outboxEventIds.add(insertTransitionEvent(
                        connection, first, policy, rule, stored, transition));
            }
        }
        return List.copyOf(outboxEventIds);
    }

    /**
     * 在遥测事务内锁定策略镜像，并拒绝事务开始前已经过期的策略对象。
     *
     * @param connection 调用方事务连接
     * @param deviceIds 本批全部设备标识，包含事务外读取时无策略的设备
     * @param policies 消费线程在事务外批量读取到的非空策略
     * @throws SQLException 策略已切换、创建、删除或无法锁定
     */
    public void verifyCurrentPolicies(
            Connection connection,
            Collection<UUID> deviceIds,
            Map<UUID, AlarmPolicy> policies
    ) throws SQLException {
        Objects.requireNonNull(connection, "告警事务连接不能为空");
        Objects.requireNonNull(deviceIds, "告警策略设备集合不能为空");
        Objects.requireNonNull(policies, "告警策略映射不能为空");
        List<UUID> orderedDeviceIds = deviceIds.stream()
                .map(deviceId -> Objects.requireNonNull(deviceId, "告警策略设备标识不能为空"))
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        for (UUID deviceId : orderedDeviceIds) {
            AlarmPolicyAdvisoryLock.lockShared(connection, deviceId);
        }
        Map<UUID, PolicyIdentity> current = findCurrentPolicies(connection, orderedDeviceIds);
        for (UUID deviceId : orderedDeviceIds) {
            AlarmPolicy expected = policies.get(deviceId);
            PolicyIdentity actual = current.get(deviceId);
            boolean matches = expected == null
                    ? actual == null
                    : actual != null
                    && actual.runtimeRevision() == expected.runtimeRevision()
                    && actual.snapshotId().equals(expected.snapshotId())
                    && actual.contentHash().equals(expected.contentHash());
            if (!matches) {
                throw new SQLException("告警策略在遥测事务开始前已经切换、创建或删除");
            }
        }
    }

    private Map<UUID, PolicyIdentity> findCurrentPolicies(
            Connection connection,
            List<UUID> deviceIds
    ) throws SQLException {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(
                deviceIds.size(), "?"));
        String sql = """
                SELECT device_id, runtime_revision, snapshot_id, content_hash
                  FROM telemetry_alarm_policy
                 WHERE device_id IN (%s)
                 ORDER BY device_id
                 FOR SHARE
                """.formatted(placeholders);
        Map<UUID, PolicyIdentity> current = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (UUID deviceId : deviceIds) {
                statement.setObject(index++, deviceId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    current.put(
                            resultSet.getObject("device_id", UUID.class),
                            new PolicyIdentity(
                                    resultSet.getLong("runtime_revision"),
                                    resultSet.getObject("snapshot_id", UUID.class),
                                    resultSet.getString("content_hash")));
                }
            }
        }
        return Map.copyOf(current);
    }

    private StoredState findState(Connection connection, UUID deviceId, String ruleKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_STATE_SQL)) {
            statement.setObject(1, deviceId);
            statement.setString(2, ruleKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new StoredState(
                        resultSet.getString("product_key"), ruleKey,
                        resultSet.getString("rule_name"),
                        resultSet.getString("source_type"),
                        resultSet.getString("severity"),
                        resultSet.getString("property_id"),
                        new AlarmStateMachine.State(
                                AlarmStateMachine.Status.valueOf(resultSet.getString("state")),
                                instant(resultSet, "candidate_since"),
                                resultSet.getObject("alarm_occurrence_id", UUID.class),
                                instant(resultSet, "raised_at"),
                                instant(resultSet, "recovered_at"),
                                instant(resultSet, "cooldown_until"),
                                instant(resultSet, "last_received_at")),
                        resultSet.getString("last_observation"),
                        resultSet.getLong("policy_runtime_revision"),
                        resultSet.getObject("policy_snapshot_id", UUID.class),
                        resultSet.getString("policy_content_hash"),
                        resultSet.getString("rule_fingerprint"));
            }
        }
    }

    private void appendChangedRecovery(
            Connection connection,
            TelemetrySample sample,
            StoredState stored,
            List<UUID> eventIds
    ) throws SQLException {
        AlarmStateMachine.State state = stored.state();
        if (state.status() != AlarmStateMachine.Status.ACTIVE
                && state.status() != AlarmStateMachine.Status.RECOVERING_PENDING) {
            return;
        }
        AlarmRecoveredPayload payload = new AlarmRecoveredPayload(
                state.occurrenceId(), sample.deviceId(), stored.productKey(),
                stored.ruleKey(), stored.ruleName(), stored.sourceType(), stored.severity(),
                stored.propertyId(), readEvidence(stored.lastObservationJson()),
                stored.policyRuntimeRevision(), stored.policySnapshotId(),
                stored.policyContentHash(), state.raisedAt(), sample.receivedAt(),
                "RULE_CHANGED");
        eventIds.add(insertOutbox(
                connection, sample, state.occurrenceId(), "alarm.recovered",
                sample.receivedAt(), payload));
    }

    private UUID insertTransitionEvent(
            Connection connection,
            TelemetrySample sample,
            AlarmPolicy policy,
            AlarmPolicy.Rule rule,
            StoredState stored,
            AlarmStateMachine.Transition transition
    ) throws SQLException {
        AlarmStateMachine.Event event = transition.event();
        boolean preserveStoredIdentity = event.type() == AlarmStateMachine.EventType.RECOVERED
                && sameOccurrence(stored, transition.state());
        Object payload = event.type() == AlarmStateMachine.EventType.RAISED
                ? new AlarmRaisedPayload(
                        event.occurrenceId(), sample.deviceId(), sample.productKey(),
                        rule.key(), rule.name(), rule.sourceType().name(), rule.severity().name(),
                        rule.propertyId(), event.evidence(), policy.runtimeRevision(),
                        policy.snapshotId(), policy.contentHash(), event.occurredAt())
                : new AlarmRecoveredPayload(
                        event.occurrenceId(), sample.deviceId(),
                        preserveStoredIdentity ? stored.productKey() : sample.productKey(),
                        rule.key(), preserveStoredIdentity ? stored.ruleName() : rule.name(),
                        preserveStoredIdentity ? stored.sourceType() : rule.sourceType().name(),
                        preserveStoredIdentity ? stored.severity() : rule.severity().name(),
                        preserveStoredIdentity ? stored.propertyId() : rule.propertyId(),
                        event.evidence(),
                        preserveStoredIdentity
                                ? stored.policyRuntimeRevision() : policy.runtimeRevision(),
                        preserveStoredIdentity ? stored.policySnapshotId() : policy.snapshotId(),
                        preserveStoredIdentity ? stored.policyContentHash() : policy.contentHash(),
                        transition.state().raisedAt(),
                        event.occurredAt(), event.recoveryReason());
        String eventType = event.type() == AlarmStateMachine.EventType.RAISED
                ? "alarm.raised" : "alarm.recovered";
        return insertOutbox(
                connection, sample, event.occurrenceId(), eventType,
                event.occurredAt(), payload);
    }

    private UUID insertOutbox(
            Connection connection,
            TelemetrySample sample,
            UUID occurrenceId,
            String eventType,
            Instant occurredAt,
            Object payload
    ) throws SQLException {
        return outboxWriter.insert(
                connection, sample.eventId(), sample.deviceId(), occurrenceId,
                eventType, occurredAt, payload);
    }

    private void upsertState(
            Connection connection,
            TelemetrySample sample,
            AlarmPolicy policy,
            AlarmPolicy.Rule rule,
            String fingerprint,
            StoredState stored,
            AlarmStateMachine.State state,
            String observation
    ) throws SQLException {
        boolean preserveStoredIdentity = sameOccurrence(stored, state);
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_STATE_SQL)) {
            int index = 1;
            statement.setObject(index++, sample.deviceId());
            statement.setString(index++, preserveStoredIdentity
                    ? stored.productKey() : sample.productKey());
            statement.setString(index++, rule.key());
            statement.setString(index++, preserveStoredIdentity ? stored.ruleName() : rule.name());
            statement.setString(index++, preserveStoredIdentity
                    ? stored.sourceType() : rule.sourceType().name());
            statement.setString(index++, preserveStoredIdentity
                    ? stored.severity() : rule.severity().name());
            setNullableString(statement, index++, preserveStoredIdentity
                    ? stored.propertyId() : rule.propertyId());
            statement.setString(index++, state.status().name());
            setNullableUuid(statement, index++, state.occurrenceId());
            setNullableInstant(statement, index++, state.candidateSince());
            setNullableInstant(statement, index++, state.raisedAt());
            setNullableInstant(statement, index++, state.recoveredAt());
            setNullableInstant(statement, index++, state.cooldownUntil());
            statement.setTimestamp(index++, Timestamp.from(state.lastReceivedAt()));
            statement.setString(index++, observation);
            statement.setLong(index++, preserveStoredIdentity
                    ? stored.policyRuntimeRevision() : policy.runtimeRevision());
            statement.setObject(index++, preserveStoredIdentity
                    ? stored.policySnapshotId() : policy.snapshotId());
            statement.setString(index++, preserveStoredIdentity
                    ? stored.policyContentHash() : policy.contentHash());
            statement.setString(index, fingerprint);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("告警运行状态写入失败");
            }
        }
    }

    private boolean sameOccurrence(
            StoredState stored,
            AlarmStateMachine.State state
    ) {
        return stored != null
                && stored.state().occurrenceId() != null
                && stored.state().occurrenceId().equals(state.occurrenceId());
    }

    private void deleteState(Connection connection, UUID deviceId, String ruleKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_STATE_SQL)) {
            statement.setObject(1, deviceId);
            statement.setString(2, ruleKey);
            statement.executeUpdate();
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void setNullableUuid(PreparedStatement statement, int index, UUID value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }

    private void setNullableInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("告警运行数据无法序列化", exception);
        }
    }

    private AlarmEvidencePayload readEvidence(String json) {
        try {
            return jsonMapper.readValue(json, AlarmEvidencePayload.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("已持久化告警证据无法解析", exception);
        }
    }

    private record StoredState(
            String productKey,
            String ruleKey,
            String ruleName,
            String sourceType,
            String severity,
            String propertyId,
            AlarmStateMachine.State state,
            String lastObservationJson,
            long policyRuntimeRevision,
            UUID policySnapshotId,
            String policyContentHash,
            String ruleFingerprint
    ) {
    }

    private record PolicyIdentity(
            long runtimeRevision,
            UUID snapshotId,
            String contentHash
    ) {
    }
}
