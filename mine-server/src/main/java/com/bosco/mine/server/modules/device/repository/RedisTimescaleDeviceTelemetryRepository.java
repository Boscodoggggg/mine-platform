package com.bosco.mine.server.modules.device.repository;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.server.modules.device.infrastructure.TelemetryQueryConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 Redis 查询最新值，并使用 TimescaleDB 查询原始和聚合遥测历史。
 */
@Repository
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class RedisTimescaleDeviceTelemetryRepository implements DeviceTelemetryRepository {

    private static final String LATEST_KEY_PREFIX = "mine:latest:";
    private static final String LATEST_ORDER_KEY_PREFIX = "mine:latest-order:";
    private static final String LATEST_STATE_KEY_PREFIX = "mine:latest-state:";
    private static final String RETIRED_BOOT_KEY_PREFIX = "mine:latest-retired-boots:";
    private static final String FIND_LATEST_SQL = """
            SELECT property_id, value_double, occurred_at, effective_at, received_at,
                   quality, event_id, boot_id, sequence
            FROM telemetry_latest_numeric
            WHERE device_id = ?
              AND property_id = ?
              AND value_double IS NOT NULL
            """;
    private static final String FIND_LATEST_STATE_SQL = """
            SELECT current_boot_id, highest_sequence, latest_effective_at, latest_event_id
            FROM telemetry_latest_state
            WHERE device_id = ?
            """;
    private static final String FIND_RETIRED_BOOTS_SQL = """
            SELECT boot_id
            FROM telemetry_retired_boot
            WHERE device_id = ?
            ORDER BY boot_id
            """;
    private static final DefaultRedisScript<Long> RESTORE_LATEST_STATE =
            new DefaultRedisScript<>("""
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        if ARGV[1] ~= '' then
                            redis.call('HSET', KEYS[1], 'currentBootId', ARGV[1])
                        end
                        if tonumber(ARGV[2]) >= 0 then
                            redis.call('HSET', KEYS[1], 'highestSequence', ARGV[2])
                        end
                        if tonumber(ARGV[3]) >= 0 then
                            redis.call('HSET', KEYS[1], 'latestEffectiveAt', ARGV[3])
                        end
                        if ARGV[4] ~= '' then
                            redis.call('HSET', KEYS[1], 'latestEventId', ARGV[4])
                        end
                    end
                    local retiredCount = tonumber(ARGV[5])
                    for index = 1, retiredCount do
                        redis.call('SADD', KEYS[2], ARGV[5 + index])
                    end
                    return 1
                    """, Long.class);
    private static final DefaultRedisScript<Long> RESTORE_LATEST_IF_ABSENT =
            new DefaultRedisScript<>("""
                    local propertyId = ARGV[1]
                    local valueJson = ARGV[2]
                    local incomingTime = tonumber(ARGV[3])
                    local bootId = ARGV[4]
                    local sequence = tonumber(ARGV[5])
                    local eventId = ARGV[6]

                    local inserted = redis.call('HSETNX', KEYS[1], propertyId, valueJson)
                    if inserted == 1 then
                        redis.call('HSET', KEYS[2], propertyId,
                                incomingTime .. '|' .. bootId .. '|' .. sequence .. '|' .. eventId)
                    end
                    return inserted
                    """, Long.class);
    private static final String COUNT_RAW_SQL = """
            SELECT COUNT(*)
            FROM telemetry_numeric
            WHERE device_id = ?
              AND property_id = ?
              AND occurred_at >= ?
              AND occurred_at < ?
              AND ingest_id <= ?
              AND value_double IS NOT NULL
            """;
    private static final String FIND_RAW_SQL = """
            SELECT occurred_at, received_at, value_double, quality, event_id
            FROM telemetry_numeric
            WHERE device_id = ?
              AND property_id = ?
              AND occurred_at >= ?
              AND occurred_at < ?
              AND ingest_id <= ?
              AND value_double IS NOT NULL
            ORDER BY occurred_at, event_id
            OFFSET ? LIMIT ?
            """;
    private static final String BUCKET_EXPRESSION =
            "date_bin(CAST(? AS interval), occurred_at, TIMESTAMPTZ '1970-01-01 00:00:00Z')";

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate telemetryJdbcTemplate;
    private final TransactionTemplate readOnlyTransaction;
    private final JsonMapper jsonMapper;

    /**
     * 创建设备遥测查询仓储。
     *
     * @param redisTemplate Redis 字符串操作模板
     * @param telemetryQueryConnection 遥测时序库受管连接
     */
    @Autowired
    public RedisTimescaleDeviceTelemetryRepository(
            StringRedisTemplate redisTemplate,
            TelemetryQueryConnection telemetryQueryConnection
    ) {
        this(
                redisTemplate,
                telemetryQueryConnection.jdbcTemplate(),
                telemetryQueryConnection.readOnlyTransaction()
        );
    }

    /**
     * 供仓储集成测试注入独立 JDBC 模板。
     *
     * @param redisTemplate Redis 字符串操作模板
     * @param telemetryJdbcTemplate 遥测时序库 JDBC 模板
     */
    RedisTimescaleDeviceTelemetryRepository(
            StringRedisTemplate redisTemplate,
            JdbcTemplate telemetryJdbcTemplate
    ) {
        this(redisTemplate, telemetryJdbcTemplate, transactionFor(telemetryJdbcTemplate));
    }

    private RedisTimescaleDeviceTelemetryRepository(
            StringRedisTemplate redisTemplate,
            JdbcTemplate telemetryJdbcTemplate,
            TransactionTemplate readOnlyTransaction
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "Redis 操作模板不能为空");
        this.telemetryJdbcTemplate = Objects.requireNonNull(
                telemetryJdbcTemplate, "遥测 JDBC 模板不能为空"
        );
        this.readOnlyTransaction = Objects.requireNonNull(
                readOnlyTransaction, "遥测只读事务模板不能为空"
        );
        this.jsonMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<TelemetryValue> findCachedLatest(UUID deviceId, String propertyId) {
        Object json = redisTemplate.opsForHash().get(latestKey(deviceId), propertyId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            CachedLatestValue cached = jsonMapper.readValue(json.toString(), CachedLatestValue.class);
            return Optional.of(cached.toTelemetryValue(propertyId));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Redis 最新遥测值反序列化失败", exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<TelemetryValue> findLatestHistory(UUID deviceId, String propertyId) {
        List<TelemetryValue> values = telemetryJdbcTemplate.query(
                FIND_LATEST_SQL,
                (resultSet, rowNumber) -> mapTelemetryValue(resultSet),
                deviceId,
                propertyId
        );
        return values.stream().findFirst();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, TelemetryValue> findLatestHistory(
            UUID deviceId,
            List<String> propertyIds
    ) {
        if (propertyIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(propertyIds.size(), "?"));
        String sql = """
                SELECT property_id, value_double, occurred_at, effective_at, received_at,
                       quality, event_id, boot_id, sequence
                FROM telemetry_latest_numeric
                WHERE device_id = ?
                  AND property_id IN (%s)
                  AND value_double IS NOT NULL
                ORDER BY property_id
                """.formatted(placeholders);
        List<Object> arguments = new ArrayList<>(propertyIds.size() + 1);
        arguments.add(deviceId);
        arguments.addAll(propertyIds);
        LinkedHashMap<String, TelemetryValue> values = new LinkedHashMap<>();
        telemetryJdbcTemplate.query(
                sql,
                resultSet -> {
                    TelemetryValue value = mapTelemetryValue(resultSet);
                    values.put(value.propertyId(), value);
                },
                arguments.toArray()
        );
        return Map.copyOf(values);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void restoreLatestState(UUID deviceId) {
        List<DurableLatestState> states = telemetryJdbcTemplate.query(
                FIND_LATEST_STATE_SQL,
                (resultSet, rowNumber) -> {
                    long rawSequence = resultSet.getLong("highest_sequence");
                    Long sequence = resultSet.wasNull() ? null : rawSequence;
                    Timestamp rawEffectiveAt = resultSet.getTimestamp("latest_effective_at");
                    return new DurableLatestState(
                            resultSet.getString("current_boot_id"),
                            sequence,
                            rawEffectiveAt == null ? null : rawEffectiveAt.toInstant(),
                            resultSet.getObject("latest_event_id", UUID.class)
                    );
                },
                deviceId
        );
        if (states.isEmpty()) {
            return;
        }
        List<String> retiredBoots = telemetryJdbcTemplate.queryForList(
                FIND_RETIRED_BOOTS_SQL, String.class, deviceId
        );
        DurableLatestState state = states.getFirst();
        List<String> arguments = new ArrayList<>(5 + retiredBoots.size());
        arguments.add(state.currentBootId() == null ? "" : state.currentBootId());
        arguments.add(Long.toString(state.highestSequence() == null
                ? -1L : state.highestSequence()));
        arguments.add(Long.toString(state.latestEffectiveAt() == null
                ? -1L : state.latestEffectiveAt().toEpochMilli()));
        arguments.add(state.latestEventId() == null ? "" : state.latestEventId().toString());
        arguments.add(Integer.toString(retiredBoots.size()));
        arguments.addAll(retiredBoots);
        redisTemplate.execute(
                RESTORE_LATEST_STATE,
                List.of(latestStateKey(deviceId), retiredBootKey(deviceId)),
                arguments.toArray()
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean cacheLatestIfAbsent(UUID deviceId, TelemetryValue value) {
        try {
            Long inserted = redisTemplate.execute(
                    RESTORE_LATEST_IF_ABSENT,
                    List.of(
                            latestKey(deviceId),
                            latestOrderKey(deviceId),
                            latestStateKey(deviceId),
                            retiredBootKey(deviceId)
                    ),
                    value.propertyId(),
                    jsonMapper.writeValueAsString(CachedLatestValue.from(value)),
                    Long.toString(value.effectiveAt().toEpochMilli()),
                    value.bootId() == null ? "" : value.bootId(),
                    Long.toString(value.sequence() == null ? -1L : value.sequence()),
                    value.eventId().toString()
            );
            return Long.valueOf(1L).equals(inserted);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Timescale 回源值序列化失败", exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long currentIngestionWatermark() {
        Long watermark = telemetryJdbcTemplate.queryForObject(
                """
                        SELECT committed_ingest_id
                        FROM telemetry_ingestion_watermark
                        WHERE singleton_id = 1
                        """,
                Long.class
        );
        return watermark == null ? 0L : watermark;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HistoryPage findHistory(HistoryQuery query) {
        HistoryPage result = readOnlyTransaction.execute(status ->
                query.aggregation() == Aggregation.RAW
                        ? findRawHistory(query)
                        : findAggregatedHistory(query)
        );
        return Objects.requireNonNull(result, "遥测历史查询结果不能为空");
    }

    private HistoryPage findRawHistory(HistoryQuery query) {
        Long total = telemetryJdbcTemplate.queryForObject(
                COUNT_RAW_SQL,
                Long.class,
                query.deviceId(),
                query.propertyId(),
                Timestamp.from(query.from()),
                Timestamp.from(query.to()),
                query.watermark()
        );
        List<HistoryPoint> items = telemetryJdbcTemplate.query(
                FIND_RAW_SQL,
                (resultSet, rowNumber) -> new HistoryPoint(
                        resultSet.getTimestamp("occurred_at").toInstant(),
                        resultSet.getTimestamp("received_at").toInstant(),
                        resultSet.getDouble("value_double"),
                        qualityOf(resultSet.getShort("quality")),
                        resultSet.getObject("event_id", UUID.class),
                        1L
                ),
                query.deviceId(),
                query.propertyId(),
                Timestamp.from(query.from()),
                Timestamp.from(query.to()),
                query.watermark(),
                query.offset(),
                query.limit()
        );
        return new HistoryPage(items, total == null ? 0L : total);
    }

    private HistoryPage findAggregatedHistory(HistoryQuery query) {
        String interval = intervalLiteral(query.bucket());
        String countSql = """
                SELECT COUNT(*)
                FROM (
                    SELECT %s AS bucket_start
                    FROM telemetry_numeric
                    WHERE device_id = ?
                      AND property_id = ?
                      AND occurred_at >= ?
                      AND occurred_at < ?
                      AND ingest_id <= ?
                      AND value_double IS NOT NULL
                    GROUP BY bucket_start
                ) buckets
                """.formatted(BUCKET_EXPRESSION);
        Long total = telemetryJdbcTemplate.queryForObject(
                countSql,
                Long.class,
                interval,
                query.deviceId(),
                query.propertyId(),
                Timestamp.from(query.from()),
                Timestamp.from(query.to()),
                query.watermark()
        );
        String valueExpression = switch (query.aggregation()) {
            case AVG -> "AVG(value_double)";
            case MIN -> "MIN(value_double)";
            case MAX -> "MAX(value_double)";
            case RAW -> throw new IllegalArgumentException("原始查询不能进入聚合 SQL");
        };
        String selectSql = """
                SELECT %s AS bucket_start,
                       MAX(received_at) AS received_at,
                       %s AS value_result,
                       MAX(quality) AS quality,
                       COUNT(*) AS sample_count
                FROM telemetry_numeric
                WHERE device_id = ?
                  AND property_id = ?
                  AND occurred_at >= ?
                  AND occurred_at < ?
                  AND ingest_id <= ?
                  AND value_double IS NOT NULL
                GROUP BY bucket_start
                ORDER BY bucket_start
                OFFSET ? LIMIT ?
                """.formatted(BUCKET_EXPRESSION, valueExpression);
        List<HistoryPoint> items = telemetryJdbcTemplate.query(
                selectSql,
                (resultSet, rowNumber) -> new HistoryPoint(
                        resultSet.getTimestamp("bucket_start").toInstant(),
                        resultSet.getTimestamp("received_at").toInstant(),
                        resultSet.getDouble("value_result"),
                        qualityOf(resultSet.getShort("quality")),
                        null,
                        resultSet.getLong("sample_count")
                ),
                interval,
                query.deviceId(),
                query.propertyId(),
                Timestamp.from(query.from()),
                Timestamp.from(query.to()),
                query.watermark(),
                query.offset(),
                query.limit()
        );
        return new HistoryPage(items, total == null ? 0L : total);
    }

    private TelemetryValue mapTelemetryValue(ResultSet resultSet) throws SQLException {
        long rawSequence = resultSet.getLong("sequence");
        Long sequence = resultSet.wasNull() ? null : rawSequence;
        return new TelemetryValue(
                resultSet.getString("property_id"),
                resultSet.getDouble("value_double"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getTimestamp("effective_at").toInstant(),
                resultSet.getTimestamp("received_at").toInstant(),
                qualityOf(resultSet.getShort("quality")),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("boot_id"),
                sequence
        );
    }

    private Quality qualityOf(short quality) {
        return switch (quality) {
            case 0 -> Quality.GOOD;
            case 1 -> Quality.UNCERTAIN;
            case 2 -> Quality.BAD;
            case 3 -> Quality.TIME_UNTRUSTED;
            default -> throw new IllegalStateException("未知遥测质量编码: " + quality);
        };
    }

    private String intervalLiteral(Duration bucket) {
        return bucket.toSeconds() + " seconds";
    }

    private static TransactionTemplate transactionFor(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate.getDataSource() == null) {
            throw new IllegalArgumentException("遥测 JDBC 模板必须关联数据源");
        }
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private String latestKey(UUID deviceId) {
        return LATEST_KEY_PREFIX + deviceId;
    }

    private String latestOrderKey(UUID deviceId) {
        return LATEST_ORDER_KEY_PREFIX + deviceId;
    }

    private String latestStateKey(UUID deviceId) {
        return LATEST_STATE_KEY_PREFIX + deviceId;
    }

    private String retiredBootKey(UUID deviceId) {
        return RETIRED_BOOT_KEY_PREFIX + deviceId;
    }

    /**
     * 与 mine-telemetry 写入端保持字段兼容的 Redis JSON 结构。
     *
     * @param value 数值
     * @param occurredAt 设备发生时间
     * @param receivedAt 平台接收时间
     * @param quality 数据质量
     * @param eventId 事件标识
     * @param bootId 设备启动周期标识
     * @param sequence 启动周期内序号
     */
    private record CachedLatestValue(
            double value,
            Instant occurredAt,
            Instant receivedAt,
            Quality quality,
            UUID eventId,
            String bootId,
            Long sequence
    ) {

        private static CachedLatestValue from(TelemetryValue value) {
            return new CachedLatestValue(
                    value.value(),
                    value.occurredAt(),
                    value.receivedAt(),
                    value.quality(),
                    value.eventId(),
                    value.bootId(),
                    value.sequence()
            );
        }

        private TelemetryValue toTelemetryValue(String propertyId) {
            return new TelemetryValue(
                    propertyId,
                    value,
                    occurredAt,
                    quality == Quality.TIME_UNTRUSTED ? receivedAt : occurredAt,
                    receivedAt,
                    quality,
                    eventId,
                    bootId,
                    sequence
            );
        }
    }

    /**
     * TimescaleDB 中可用于完整恢复 Redis 顺序判定的设备状态。
     */
    private record DurableLatestState(
            String currentBootId,
            Long highestSequence,
            Instant latestEffectiveAt,
            UUID latestEventId
    ) {
    }
}
