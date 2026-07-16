package com.bosco.mine.telemetry.storage;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 在遥测历史事务内维护可灾后恢复的最新值读模型和启动周期状态。
 */
final class DurableLatestWriter {

    private static final String ENSURE_STATE_SQL = """
            INSERT INTO telemetry_latest_state (device_id)
            VALUES (?)
            ON CONFLICT (device_id) DO NOTHING
            """;
    private static final String LOCK_STATE_SQL = """
            SELECT current_boot_id, highest_sequence, latest_effective_at, latest_event_id
            FROM telemetry_latest_state
            WHERE device_id = ?
            FOR UPDATE
            """;
    private static final String UPDATE_ACTIVITY_SQL = """
            UPDATE telemetry_latest_state
            SET first_seen_at = COALESCE(first_seen_at, ?),
                last_received_at = CASE
                    WHEN last_received_at IS NULL THEN ?
                    ELSE GREATEST(last_received_at, ?)
                END
            WHERE device_id = ?
            """;
    private static final String FIND_RETIRED_BOOT_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM telemetry_retired_boot WHERE device_id = ? AND boot_id = ?
            )
            """;
    private static final String RETIRE_BOOT_SQL = """
            INSERT INTO telemetry_retired_boot (device_id, boot_id)
            VALUES (?, ?)
            ON CONFLICT (device_id, boot_id) DO NOTHING
            """;
    private static final String UPDATE_STATE_SQL = """
            UPDATE telemetry_latest_state
            SET current_boot_id = ?, highest_sequence = ?, latest_effective_at = ?,
                latest_event_id = ?, updated_at = CURRENT_TIMESTAMP
            WHERE device_id = ?
            """;
    private static final String UPSERT_VALUE_SQL = """
            INSERT INTO telemetry_latest_numeric (
                device_id, property_id, value_double, occurred_at, effective_at, received_at,
                quality, boot_id, sequence, event_id, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (device_id, property_id) DO UPDATE SET
                value_double = EXCLUDED.value_double,
                occurred_at = EXCLUDED.occurred_at,
                effective_at = EXCLUDED.effective_at,
                received_at = EXCLUDED.received_at,
                quality = EXCLUDED.quality,
                boot_id = EXCLUDED.boot_id,
                sequence = EXCLUDED.sequence,
                event_id = EXCLUDED.event_id,
                updated_at = CURRENT_TIMESTAMP
            """;
    private static final String IS_CURRENT_EVENT_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM telemetry_latest_state
                WHERE device_id = ? AND latest_event_id = ?
            )
            """;

    /**
     * 按设备串行判定一帧是否能够更新持久化最新值。
     *
     * @param connection 当前遥测历史事务连接
     * @param samples 同一设备事件的数值遥测点
     * @return 最新值更新结果和由持久顺序状态确认的序列缺口
     * @throws SQLException 数据库访问失败
     */
    ApplyResult apply(Connection connection, List<TelemetrySample> samples) throws SQLException {
        Objects.requireNonNull(connection, "数据库连接不能为空");
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("最新值事件不能为空");
        }
        TelemetrySample first = samples.getFirst();
        ensureState(connection, first.deviceId());
        updateActivity(connection, first.deviceId(), first.receivedAt());
        State state = readState(connection, first.deviceId());
        Decision decision = decide(connection, first, state);
        if (decision.orderingStateAdvanced() && !decision.updateLatest()) {
            updateState(connection, first.deviceId(), decision.state());
        }
        if (!decision.updateLatest()) {
            return new ApplyResult(false, decision.sequenceGap());
        }
        if (decision.bootToRetire() != null) {
            retireBoot(connection, first.deviceId(), decision.bootToRetire());
        }
        updateState(connection, first.deviceId(), decision.state());
        upsertValues(connection, samples);
        return new ApplyResult(true, decision.sequenceGap());
    }

    /**
     * 判断幂等重投事件是否仍为设备当前生效事件，用于数据库已提交但 Redis 刷新失败后的安全重试。
     *
     * @param connection 当前遥测历史事务连接
     * @param deviceId 设备标识
     * @param eventId 事件标识
     * @return 事件仍为当前生效事件时返回 {@code true}
     * @throws SQLException 数据库访问失败
     */
    boolean isCurrentEvent(Connection connection, UUID deviceId, UUID eventId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(IS_CURRENT_EVENT_SQL)) {
            statement.setObject(1, deviceId);
            statement.setObject(2, eventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private void ensureState(Connection connection, UUID deviceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ENSURE_STATE_SQL)) {
            statement.setObject(1, deviceId);
            statement.executeUpdate();
        }
    }

    private State readState(Connection connection, UUID deviceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_STATE_SQL)) {
            statement.setObject(1, deviceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("设备最新值状态不存在");
                }
                long rawSequence = resultSet.getLong("highest_sequence");
                Long highestSequence = resultSet.wasNull() ? null : rawSequence;
                Timestamp rawEffectiveAt = resultSet.getTimestamp("latest_effective_at");
                return new State(
                        resultSet.getString("current_boot_id"),
                        highestSequence,
                        rawEffectiveAt == null ? null : rawEffectiveAt.toInstant(),
                        resultSet.getObject("latest_event_id", UUID.class)
                );
            }
        }
    }

    private void updateActivity(Connection connection, UUID deviceId, Instant receivedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_ACTIVITY_SQL)) {
            Timestamp value = Timestamp.from(receivedAt);
            statement.setTimestamp(1, value);
            statement.setTimestamp(2, value);
            statement.setTimestamp(3, value);
            statement.setObject(4, deviceId);
            statement.executeUpdate();
        }
    }

    private Decision decide(
            Connection connection,
            TelemetrySample sample,
            State state
    ) throws SQLException {
        boolean reliable = sample.bootId() != null && sample.sequence() != null;
        boolean sameBoot = reliable && sample.bootId().equals(state.currentBootId());
        boolean newBoot = reliable && state.currentBootId() != null && !sameBoot;
        boolean firstReliableBoot = reliable && state.currentBootId() == null;
        if (reliable && isRetired(connection, sample.deviceId(), sample.bootId())) {
            return Decision.rejected(state);
        }
        if (sameBoot && state.highestSequence() != null
                && sample.sequence() <= state.highestSequence()) {
            return Decision.rejected(state);
        }
        TelemetryBatchWriter.SequenceGap sequenceGap = sameBoot
                && state.highestSequence() != null
                && sample.sequence() > state.highestSequence() + 1L
                ? new TelemetryBatchWriter.SequenceGap(
                        sample.eventId(), sample.deviceId(), sample.bootId(),
                        state.highestSequence(), sample.sequence(),
                        sample.sequence() - state.highestSequence() - 1L
                )
                : null;

        boolean wins = state.latestEffectiveAt() == null
                || sample.effectiveAt().isAfter(state.latestEffectiveAt());
        if (!wins && sample.effectiveAt().equals(state.latestEffectiveAt())) {
            wins = reliable && (firstReliableBoot || newBoot || sameBoot)
                    || !reliable && compareEvent(sample.eventId(), state.latestEventId()) > 0;
        }

        boolean orderingStateAdvanced = sameBoot || firstReliableBoot;
        if (!wins) {
            State advanced = orderingStateAdvanced
                    ? new State(
                            sample.bootId(), sample.sequence(), state.latestEffectiveAt(),
                            state.latestEventId()
                    )
                    : state;
            return new Decision(false, orderingStateAdvanced, advanced, null, sequenceGap);
        }

        String currentBoot = reliable ? sample.bootId() : state.currentBootId();
        Long highestSequence = reliable ? sample.sequence() : state.highestSequence();
        State accepted = new State(
                currentBoot,
                highestSequence,
                sample.effectiveAt(),
                sample.eventId()
        );
        return new Decision(
                true, orderingStateAdvanced, accepted, newBoot ? state.currentBootId() : null,
                sequenceGap
        );
    }

    private boolean isRetired(Connection connection, UUID deviceId, String bootId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_RETIRED_BOOT_SQL)) {
            statement.setObject(1, deviceId);
            statement.setString(2, bootId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private int compareEvent(UUID left, UUID right) {
        return right == null ? 1 : left.toString().compareTo(right.toString());
    }

    private void retireBoot(Connection connection, UUID deviceId, String bootId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RETIRE_BOOT_SQL)) {
            statement.setObject(1, deviceId);
            statement.setString(2, bootId);
            statement.executeUpdate();
        }
    }

    private void updateState(Connection connection, UUID deviceId, State state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_STATE_SQL)) {
            statement.setString(1, state.currentBootId());
            if (state.highestSequence() == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, state.highestSequence());
            }
            statement.setTimestamp(3, state.latestEffectiveAt() == null
                    ? null : Timestamp.from(state.latestEffectiveAt()));
            statement.setObject(4, state.latestEventId());
            statement.setObject(5, deviceId);
            statement.executeUpdate();
        }
    }

    private void upsertValues(Connection connection, List<TelemetrySample> samples)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_VALUE_SQL)) {
            for (TelemetrySample sample : samples) {
                statement.setObject(1, sample.deviceId());
                statement.setString(2, sample.propertyId());
                statement.setDouble(3, sample.value());
                statement.setTimestamp(4, Timestamp.from(sample.occurredAt()));
                statement.setTimestamp(5, Timestamp.from(sample.effectiveAt()));
                statement.setTimestamp(6, Timestamp.from(sample.receivedAt()));
                statement.setShort(7, qualityCode(sample.quality()));
                statement.setString(8, sample.bootId());
                if (sample.sequence() == null) {
                    statement.setNull(9, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(9, sample.sequence());
                }
                statement.setObject(10, sample.eventId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private short qualityCode(Quality quality) {
        return switch (quality) {
            case GOOD -> 0;
            case UNCERTAIN -> 1;
            case BAD -> 2;
            case TIME_UNTRUSTED -> 3;
        };
    }

    private record State(
            String currentBootId,
            Long highestSequence,
            Instant latestEffectiveAt,
            UUID latestEventId
    ) {
    }

    private record Decision(
            boolean updateLatest,
            boolean orderingStateAdvanced,
            State state,
            String bootToRetire,
            TelemetryBatchWriter.SequenceGap sequenceGap
    ) {

        private static Decision rejected(State state) {
            return new Decision(false, false, state, null, null);
        }
    }

    /**
     * 表示一帧事件对最新值和序列缺口状态的应用结果。
     *
     * @param latestUpdated 是否更新了持久最新值
     * @param sequenceGap 本事件确认的序列缺口，没有缺口时为空
     */
    record ApplyResult(
            boolean latestUpdated,
            TelemetryBatchWriter.SequenceGap sequenceGap
    ) {
    }
}
