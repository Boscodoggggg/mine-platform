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
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * 在一个数据库事务中声明事件幂等键并批量写入 TimescaleDB 数值遥测历史。
 */
public final class TelemetryBatchWriter {

    private static final String CLAIM_EVENT_SQL = """
            INSERT INTO telemetry_inbox (
                event_id, device_id, message_type, boot_id, sequence, received_at, ingest_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;
    private static final String INSERT_HISTORY_SQL = """
            INSERT INTO telemetry_numeric (
                time, occurred_at, device_id, product_key, property_id, value_double,
                quality, boot_id, sequence, event_id, received_at, ingest_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;
    private static final String LOCK_INGESTION_SQL = """
            SELECT committed_ingest_id
            FROM telemetry_ingestion_watermark
            WHERE singleton_id = 1
            FOR UPDATE
            """;
    private static final String NEXT_INGESTION_SQL =
            "SELECT nextval('telemetry_ingest_seq')";
    private static final String UPDATE_INGESTION_SQL = """
            UPDATE telemetry_ingestion_watermark
            SET committed_ingest_id = ?, updated_at = CURRENT_TIMESTAMP
            WHERE singleton_id = 1
            """;
    private static final String FIND_EVENT_INGESTION_SQL = """
            SELECT ingest_id
            FROM telemetry_inbox
            WHERE event_id = ?
            """;

    private final DataSource dataSource;
    private final DurableLatestWriter latestWriter;

    /**
     * 创建遥测历史批写器。
     *
     * @param dataSource 遥测时序数据库连接池
     */
    public TelemetryBatchWriter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "数据源不能为空");
        this.latestWriter = new DurableLatestWriter();
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
            return new WriteResult(0, 0, Set.of(), Map.of(), List.of());
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
        Set<UUID> latestEventIds = new LinkedHashSet<>();
        Map<UUID, Long> latestEventIngestIds = new LinkedHashMap<>();
        List<SequenceGap> sequenceGaps = new ArrayList<>();
        lockIngestionOrder(connection);
        long latestIngestId = -1L;
        try (PreparedStatement claim = connection.prepareStatement(CLAIM_EVENT_SQL);
             PreparedStatement history = connection.prepareStatement(INSERT_HISTORY_SQL)) {
            for (List<TelemetrySample> eventSamples : events.values()) {
                TelemetrySample first = eventSamples.getFirst();
                long ingestId = nextIngestId(connection);
                latestIngestId = ingestId;
                bindClaim(claim, first, ingestId);
                if (claim.executeUpdate() == 0) {
                    if (latestWriter.isCurrentEvent(
                            connection, first.deviceId(), first.eventId()
                    )) {
                        latestEventIds.add(first.eventId());
                        latestEventIngestIds.put(
                                first.eventId(), findEventIngestId(connection, first.eventId())
                        );
                    }
                    continue;
                }
                claimedEvents++;
                DurableLatestWriter.ApplyResult latestResult = latestWriter.apply(
                        connection, eventSamples
                );
                if (latestResult.sequenceGap() != null) {
                    sequenceGaps.add(latestResult.sequenceGap());
                }
                if (latestResult.latestUpdated()) {
                    latestEventIds.add(first.eventId());
                    latestEventIngestIds.put(first.eventId(), ingestId);
                }
                for (TelemetrySample sample : eventSamples) {
                    bindHistory(history, sample, ingestId);
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
        if (latestIngestId >= 0L) {
            updateCommittedIngestion(connection, latestIngestId);
        }
        return new WriteResult(
                claimedEvents, writtenPoints, latestEventIds, latestEventIngestIds, sequenceGaps
        );
    }

    private long findEventIngestId(Connection connection, UUID eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_EVENT_INGESTION_SQL)) {
            statement.setObject(1, eventId);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("幂等遥测事件缺少摄取序号");
                }
                return resultSet.getLong(1);
            }
        }
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

    private void bindClaim(
            PreparedStatement statement,
            TelemetrySample sample,
            long ingestId
    ) throws SQLException {
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
        statement.setLong(7, ingestId);
    }

    private void bindHistory(
            PreparedStatement statement,
            TelemetrySample sample,
            long ingestId
    ) throws SQLException {
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
        statement.setLong(12, ingestId);
    }

    private void lockIngestionOrder(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_INGESTION_SQL);
             java.sql.ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("遥测摄取水位单例记录不存在");
            }
        }
    }

    private long nextIngestId(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(NEXT_INGESTION_SQL);
             java.sql.ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("无法生成遥测摄取水位");
            }
            return resultSet.getLong(1);
        }
    }

    private void updateCommittedIngestion(Connection connection, long ingestId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_INGESTION_SQL)) {
            statement.setLong(1, ingestId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("遥测摄取水位更新失败");
            }
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
     * @param latestEventIds 本次应刷新 Redis 的当前生效事件标识集合，包含安全的幂等重投
     * @param latestEventIngestIds 当前生效事件对应的已提交摄取序号
     * @param sequenceGaps 本批唯一事件确认的设备序列缺口
     */
    public record WriteResult(
            int claimedEvents,
            int writtenPoints,
            Set<UUID> latestEventIds,
            Map<UUID, Long> latestEventIngestIds,
            List<SequenceGap> sequenceGaps
    ) {

        /**
         * 创建不包含序列缺口的兼容批写结果。
         *
         * @param claimedEvents 成功声明幂等键的事件数
         * @param writtenPoints 实际写入的历史点数
         * @param latestEventIds 当前生效事件标识
         * @param latestEventIngestIds 当前生效事件摄取序号
         */
        public WriteResult(
                int claimedEvents,
                int writtenPoints,
                Set<UUID> latestEventIds,
                Map<UUID, Long> latestEventIngestIds
        ) {
            this(claimedEvents, writtenPoints, latestEventIds, latestEventIngestIds, List.of());
        }

        /**
         * 创建并校验批写结果。
         */
        public WriteResult {
            if (claimedEvents < 0 || writtenPoints < 0) {
                throw new IllegalArgumentException("批写结果计数不能为负数");
            }
            latestEventIds = Set.copyOf(Objects.requireNonNull(
                    latestEventIds, "最新值事件标识集合不能为空"
            ));
            latestEventIngestIds = Map.copyOf(Objects.requireNonNull(
                    latestEventIngestIds, "最新值事件摄取序号不能为空"
            ));
            sequenceGaps = List.copyOf(Objects.requireNonNull(
                    sequenceGaps, "设备序列缺口集合不能为空"
            ));
            if (!latestEventIngestIds.keySet().equals(latestEventIds)
                    || latestEventIngestIds.values().stream().anyMatch(value -> value == null || value < 1L)) {
                throw new IllegalArgumentException("当前生效事件与摄取序号必须一一对应且序号大于 0");
            }
        }
    }

    /**
     * 表示由设备持久顺序状态确认的一段遥测序列缺口。
     *
     * @param eventId 形成缺口的唯一事件标识
     * @param deviceId 设备标识
     * @param bootId 设备启动周期标识
     * @param previousSequence 缺口前已接收的最高序号
     * @param incomingSequence 本次到达序号
     * @param missingCount 中间暂未到达的序号数量
     */
    public record SequenceGap(
            UUID eventId,
            UUID deviceId,
            String bootId,
            long previousSequence,
            long incomingSequence,
            long missingCount
    ) {

        /** 创建并校验设备序列缺口。 */
        public SequenceGap {
            Objects.requireNonNull(eventId, "缺口事件标识不能为空");
            Objects.requireNonNull(deviceId, "缺口设备标识不能为空");
            if (bootId == null || bootId.isBlank()) {
                throw new IllegalArgumentException("缺口启动周期标识不能为空");
            }
            if (previousSequence < 0L || incomingSequence <= previousSequence
                    || missingCount < 1L
                    || missingCount != incomingSequence - previousSequence - 1L) {
                throw new IllegalArgumentException("设备序列缺口范围非法");
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
