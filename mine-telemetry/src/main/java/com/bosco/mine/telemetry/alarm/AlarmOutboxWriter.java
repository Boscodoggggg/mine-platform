package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.common.id.IdGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在现有数据库事务中写入经过哈希保护的告警 Outbox 事件。
 */
final class AlarmOutboxWriter {

    private static final String INSERT_SQL = """
            INSERT INTO telemetry_outbox_event (
                event_id, aggregate_type, aggregate_id, event_type, schema_version,
                payload, payload_hash, headers, status, attempts, next_attempt_at,
                occurred_at, created_at
            ) VALUES (?, 'ALARM_OCCURRENCE', ?, ?, 1, CAST(? AS JSONB), ?,
                      CAST(? AS JSONB), 'PENDING', 0, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
            """;

    private final IdGenerator idGenerator;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    AlarmOutboxWriter(IdGenerator idGenerator) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "告警标识生成器不能为空");
    }

    UUID insert(
            Connection connection,
            UUID traceId,
            UUID deviceId,
            UUID occurrenceId,
            String eventType,
            Instant occurredAt,
            Object payload
    ) throws SQLException {
        UUID eventId = idGenerator.next();
        String payloadJson = writeJson(payload);
        String headersJson = writeJson(Map.of(
                "traceId", traceId.toString(),
                "partitionKey", deviceId.toString()));
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setObject(1, eventId);
            statement.setObject(2, occurrenceId);
            statement.setString(3, eventType);
            statement.setString(4, payloadJson);
            statement.setString(5, sha256(payloadJson));
            statement.setString(6, headersJson);
            statement.setTimestamp(7, Timestamp.from(occurredAt));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("告警 Outbox 事件写入失败");
            }
        }
        return eventId;
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("告警 Outbox 数据无法序列化", exception);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
