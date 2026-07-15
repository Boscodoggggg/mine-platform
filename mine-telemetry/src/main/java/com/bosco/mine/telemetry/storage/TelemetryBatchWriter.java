package com.bosco.mine.telemetry.storage;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在一个数据库事务中声明事件幂等键并批量写入 TimescaleDB 数值遥测历史。
 */
public final class TelemetryBatchWriter {

    private static final String CLAIM_EVENT_SQL = """
            INSERT INTO telemetry_inbox (
                event_id, device_id, message_type, boot_id, sequence, received_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;
    private static final String INSERT_HISTORY_SQL = """
            INSERT INTO telemetry_numeric (
                time, occurred_at, device_id, product_key, property_id, value_double,
                quality, boot_id, sequence, event_id, received_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private final DataSource dataSource;

    /**
     * 创建遥测历史批写器。
     *
     * @param dataSource 遥测时序数据库连接池
     */
    public TelemetryBatchWriter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "数据源不能为空");
    }

    /**
     * 按事件分组批写数值遥测。事件标识或可靠业务键已存在时，整帧按重复消息跳过。
     *
     * @param samples 待持久化的标准遥测点
     * @return 本次成功声明的事件数和实际写入的历史点数
     */
    public WriteResult write(List<TelemetrySample> samples) {
        Objects.requireNonNull(samples, "遥测点集合不能为空");
        if (samples.isEmpty()) {
            return new WriteResult(0, 0);
        }
        Map<UUID, List<TelemetrySample>> events = groupAndValidate(samples);
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                WriteResult result = writeTransaction(connection, events);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof TelemetryStorageException storageException) {
                    throw storageException;
                }
                throw new TelemetryStorageException("遥测历史批写失败", exception);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new TelemetryStorageException("无法访问遥测时序数据库", exception);
        }
    }

    private WriteResult writeTransaction(
            Connection connection,
            Map<UUID, List<TelemetrySample>> events) throws SQLException {
        int claimedEvents = 0;
        int writtenPoints = 0;
        try (PreparedStatement claim = connection.prepareStatement(CLAIM_EVENT_SQL);
             PreparedStatement history = connection.prepareStatement(INSERT_HISTORY_SQL)) {
            for (List<TelemetrySample> eventSamples : events.values()) {
                TelemetrySample first = eventSamples.getFirst();
                bindClaim(claim, first);
                if (claim.executeUpdate() == 0) {
                    continue;
                }
                claimedEvents++;
                for (TelemetrySample sample : eventSamples) {
                    bindHistory(history, sample);
                    history.addBatch();
                }
            }
            for (int updateCount : history.executeBatch()) {
                if (updateCount == Statement.SUCCESS_NO_INFO) {
                    writtenPoints++;
                } else if (updateCount > 0) {
                    writtenPoints += updateCount;
                }
            }
        }
        return new WriteResult(claimedEvents, writtenPoints);
    }

    private Map<UUID, List<TelemetrySample>> groupAndValidate(List<TelemetrySample> samples) {
        Map<UUID, List<TelemetrySample>> events = new LinkedHashMap<>();
        for (TelemetrySample sample : samples) {
            Objects.requireNonNull(sample, "遥测点不能为空");
            List<TelemetrySample> eventSamples = events.computeIfAbsent(
                    sample.eventId(), ignored -> new ArrayList<>()
            );
            if (!eventSamples.isEmpty()) {
                validateSameEvent(eventSamples.getFirst(), sample);
            }
            eventSamples.add(sample);
        }
        return events;
    }

    private void validateSameEvent(TelemetrySample first, TelemetrySample candidate) {
        if (!first.deviceId().equals(candidate.deviceId())
                || first.messageType() != candidate.messageType()
                || !Objects.equals(first.bootId(), candidate.bootId())
                || !Objects.equals(first.sequence(), candidate.sequence())
                || !first.receivedAt().equals(candidate.receivedAt())) {
            throw new IllegalArgumentException("同一事件的设备、消息类型、启动标识、序号和接收时间必须一致");
        }
    }

    private void bindClaim(PreparedStatement statement, TelemetrySample sample) throws SQLException {
        statement.setObject(1, sample.eventId());
        statement.setObject(2, sample.deviceId());
        statement.setString(3, sample.messageType().name());
        statement.setString(4, sample.bootId());
        if (sample.sequence() == null) {
            statement.setNull(5, java.sql.Types.BIGINT);
        } else {
            statement.setLong(5, sample.sequence());
        }
        statement.setTimestamp(6, Timestamp.from(sample.receivedAt()));
    }

    private void bindHistory(PreparedStatement statement, TelemetrySample sample) throws SQLException {
        statement.setTimestamp(1, Timestamp.from(sample.effectiveAt()));
        statement.setTimestamp(2, Timestamp.from(sample.occurredAt()));
        statement.setObject(3, sample.deviceId());
        statement.setString(4, sample.productKey());
        statement.setString(5, sample.propertyId());
        statement.setDouble(6, sample.value());
        statement.setShort(7, qualityCode(sample.quality()));
        statement.setString(8, sample.bootId());
        if (sample.sequence() == null) {
            statement.setNull(9, java.sql.Types.BIGINT);
        } else {
            statement.setLong(9, sample.sequence());
        }
        statement.setObject(10, sample.eventId());
        statement.setTimestamp(11, Timestamp.from(sample.receivedAt()));
    }

    private short qualityCode(Quality quality) {
        return switch (quality) {
            case GOOD -> 0;
            case UNCERTAIN -> 1;
            case BAD -> 2;
            case TIME_UNTRUSTED -> 3;
        };
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            original.addSuppressed(rollbackException);
        }
    }

    /**
     * 遥测批写结果。
     *
     * @param claimedEvents 成功声明幂等键的事件数
     * @param writtenPoints 实际写入的历史点数
     */
    public record WriteResult(int claimedEvents, int writtenPoints) {

        /**
         * 创建并校验批写结果。
         */
        public WriteResult {
            if (claimedEvents < 0 || writtenPoints < 0) {
                throw new IllegalArgumentException("批写结果计数不能为负数");
            }
        }
    }

    /**
     * 表示遥测事务批写无法完成，可由消息消费层据此触发重新入队。
     */
    public static final class TelemetryStorageException extends RuntimeException {

        /**
         * 创建遥测存储异常。
         *
         * @param message 中文错误说明
         * @param cause   原始数据库异常
         */
        public TelemetryStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
