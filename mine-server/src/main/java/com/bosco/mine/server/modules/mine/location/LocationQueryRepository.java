package com.bosco.mine.server.modules.mine.location;

import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.bosco.mine.server.modules.device.infrastructure.TelemetryQueryConnection;
import com.bosco.mine.server.modules.mine.dto.LocationTrajectoryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用 Redis 读取当前位置，并使用只读 TimescaleDB 连接查询可靠降级值和轨迹。
 */
@Repository
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class LocationQueryRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationQueryRepository.class);
    private static final String PREFIX = "mine:location:";
    private static final int DEFAULT_RAW_SCAN_LIMIT = 100_000;
    private static final DefaultRedisScript<Long> UPDATE_IF_NEWER = new DefaultRedisScript<>("""
            local currentQuality = redis.call('HGET', KEYS[1], 'quality')
            local currentOccurredAt = tonumber(redis.call('HGET', KEYS[1], 'occurredAt'))
            local currentSequence = tonumber(redis.call('HGET', KEYS[1], 'ingestSequence'))
            local incomingQuality = ARGV[1]
            local incomingOccurredAt = tonumber(ARGV[2])
            local incomingSequence = tonumber(ARGV[3])
            if currentQuality == 'GOOD' and incomingQuality == 'DEGRADED' then return 0 end
            if currentOccurredAt then
                if incomingOccurredAt < currentOccurredAt then return 0 end
                if incomingOccurredAt == currentOccurredAt
                        and incomingSequence <= currentSequence then return 0 end
            end
            redis.call('HSET', KEYS[1],
                    'quality', incomingQuality,
                    'occurredAt', incomingOccurredAt,
                    'ingestSequence', incomingSequence,
                    'payload', ARGV[4])
            redis.call('SADD', KEYS[2], ARGV[5])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final int rawScanLimit;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建生产位置查询仓储。
     *
     * @param redisTemplate Redis 字符串操作模板
     * @param telemetryConnection 遥测只读数据库连接
     */
    @Autowired
    public LocationQueryRepository(
            StringRedisTemplate redisTemplate,
            TelemetryQueryConnection telemetryConnection
    ) {
        this(redisTemplate, telemetryConnection.jdbcTemplate(), DEFAULT_RAW_SCAN_LIMIT);
    }

    /**
     * 创建指定原始轨迹扫描上限的查询仓储，供小规模自动化验证使用。
     *
     * @param redisTemplate Redis 字符串操作模板
     * @param jdbcTemplate 遥测只读 JDBC 模板
     * @param rawScanLimit 单页最大原始轨迹点数
     */
    public LocationQueryRepository(
            StringRedisTemplate redisTemplate,
            JdbcTemplate jdbcTemplate,
            int rawScanLimit
    ) {
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate, "位置 Redis 操作模板不能为空");
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate, "位置查询 JDBC 模板不能为空");
        if (rawScanLimit < 2 || rawScanLimit > 1_000_000) {
            throw new IllegalArgumentException("原始轨迹扫描上限必须在 2 到 1000000 之间");
        }
        this.rawScanLimit = rawScanLimit;
    }

    /**
     * 优先读取 Redis 当前位置；缓存缺失或异常时从可靠 Outbox 降级并安全回填。
     *
     * @param siteId 矿区标识
     * @param limit 最大返回数量
     * @return 当前位置查询结果
     */
    public CurrentResult findCurrent(UUID siteId, int limit) {
        Objects.requireNonNull(siteId, "矿区标识不能为空");
        requireLimit(limit, 10_000, "当前位置数量");
        try {
            CurrentResult cached = findCachedCurrent(siteId, limit);
            if (!cached.events().isEmpty()) {
                return cached;
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("位置 Redis 当前值读取失败，降级查询时序库: siteId={}", siteId);
        }
        CurrentResult durable = findDurableCurrent(siteId, limit);
        durable.events().forEach(this::cacheIfNewer);
        return durable;
    }

    /**
     * 读取矿区当前最大已提交位置摄取水位。
     *
     * @param siteId 矿区标识
     * @return 不小于零的水位
     */
    public long currentWatermark(UUID siteId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(ingest_sequence), 0)
                FROM location_point
                WHERE site_id = ?
                """, Long.class, siteId);
        return value == null ? 0L : value;
    }

    /**
     * 按固定水位、单对象和键集游标读取一页有硬上限的原始轨迹。
     *
     * @param query 已校验轨迹查询
     * @return 原始轨迹切片
     */
    public TrajectorySlice findTrajectory(TrajectoryQuery query) {
        Objects.requireNonNull(query, "轨迹查询不能为空");
        String subjectColumn = switch (query.subjectType()) {
            case TAG -> "tag_id";
            case VEHICLE -> "vehicle_id";
            case PERSON -> "person_id";
        };
        String cursorClause = query.afterOccurredAt() == null
                ? "" : " AND (time, ingest_sequence) > (?, ?)";
        String sql = """
                SELECT event_id, organization_id, site_id, source_device_id, tag_id,
                       vehicle_id, person_id, target_coordinate_system_id,
                       ingest_sequence, time, received_at, local_x, local_y, local_z,
                       speed_meters_per_second, heading_degrees, accuracy_meters,
                       quality_status, quality_reason
                FROM location_point
                WHERE site_id = ?
                  AND %s = ?
                  AND time >= ? AND time < ?
                  AND ingest_sequence <= ?
                %s
                ORDER BY time, ingest_sequence
                LIMIT ?
                """.formatted(subjectColumn, cursorClause);
        List<Object> arguments = new ArrayList<>();
        arguments.add(query.siteId());
        arguments.add(query.subjectId());
        arguments.add(Timestamp.from(query.from()));
        arguments.add(Timestamp.from(query.to()));
        arguments.add(query.watermark());
        if (query.afterOccurredAt() != null) {
            arguments.add(Timestamp.from(query.afterOccurredAt()));
            arguments.add(query.afterIngestSequence());
        }
        arguments.add(rawScanLimit + 1);
        List<LocationTrajectoryResponse.Point> rows = jdbcTemplate.query(
                sql, this::mapPoint, arguments.toArray());
        boolean hasMore = rows.size() > rawScanLimit;
        List<LocationTrajectoryResponse.Point> page = hasMore
                ? List.copyOf(rows.subList(0, rawScanLimit)) : List.copyOf(rows);
        LocationTrajectoryResponse.Point last = page.isEmpty() ? null : page.getLast();
        return new TrajectorySlice(
                page, hasMore,
                last == null ? null : last.occurredAt(),
                last == null ? null : last.ingestSequence());
    }

    private CurrentResult findCachedCurrent(UUID siteId, int limit) {
        List<UUID> tagIds = new ArrayList<>(limit + 1);
        try (Cursor<String> cursor = redisTemplate.opsForSet().scan(
                indexKey(siteId), ScanOptions.scanOptions().count(limit + 1L).build())) {
            while (cursor.hasNext() && tagIds.size() <= limit) {
                tagIds.add(UUID.fromString(cursor.next()));
            }
        }
        boolean truncated = tagIds.size() > limit;
        List<RealtimeLocationEvent> events = tagIds.stream().limit(limit)
                .map(tagId -> redisTemplate.opsForHash().get(valueKey(siteId, tagId), "payload"))
                .filter(Objects::nonNull)
                .map(value -> readEvent(value.toString()))
                .filter(event -> siteId.equals(event.siteId()))
                .sorted(currentOrder())
                .toList();
        return new CurrentResult(events, maximumSequence(events), "REDIS", truncated);
    }

    private CurrentResult findDurableCurrent(UUID siteId, int limit) {
        List<RealtimeLocationEvent> events = jdbcTemplate.query("""
                SELECT candidate.payload::TEXT
                FROM (
                    SELECT DISTINCT ON (payload->>'tagId') payload, ingest_sequence,
                           (payload->>'occurredAt')::TIMESTAMPTZ AS occurred_at
                    FROM location_realtime_outbox
                    WHERE payload->>'siteId' = ?
                    ORDER BY payload->>'tagId',
                             CASE payload->>'qualityStatus' WHEN 'GOOD' THEN 1 ELSE 0 END DESC,
                             (payload->>'occurredAt')::TIMESTAMPTZ DESC,
                             ingest_sequence DESC
                ) candidate
                ORDER BY candidate.occurred_at DESC, candidate.ingest_sequence DESC
                LIMIT ?
                """, (resultSet, rowNumber) -> readEvent(resultSet.getString(1)),
                siteId.toString(), limit + 1);
        boolean truncated = events.size() > limit;
        List<RealtimeLocationEvent> page = events.stream().limit(limit).toList();
        return new CurrentResult(page, maximumSequence(page), "TIMESCALE", truncated);
    }

    private void cacheIfNewer(RealtimeLocationEvent event) {
        try {
            redisTemplate.execute(
                    UPDATE_IF_NEWER,
                    List.of(valueKey(event.siteId(), event.tagId()), indexKey(event.siteId())),
                    event.qualityStatus().name(),
                    Long.toString(event.occurredAt().toEpochMilli()),
                    Long.toString(event.sourceSequence()),
                    writeEvent(event), event.tagId().toString());
        } catch (RuntimeException exception) {
            LOGGER.warn("位置降级结果回填 Redis 失败，不影响本次查询: eventId={}", event.eventId());
        }
    }

    private LocationTrajectoryResponse.Point mapPoint(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new LocationTrajectoryResponse.Point(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("site_id", UUID.class),
                resultSet.getObject("source_device_id", UUID.class),
                resultSet.getObject("tag_id", UUID.class),
                resultSet.getObject("vehicle_id", UUID.class),
                resultSet.getObject("person_id", UUID.class),
                resultSet.getObject("target_coordinate_system_id", UUID.class),
                resultSet.getLong("ingest_sequence"),
                resultSet.getObject("time", java.time.OffsetDateTime.class).toInstant(),
                resultSet.getObject("received_at", java.time.OffsetDateTime.class).toInstant(),
                resultSet.getDouble("local_x"), resultSet.getDouble("local_y"),
                nullableDouble(resultSet, "local_z"),
                nullableDouble(resultSet, "speed_meters_per_second"),
                nullableDouble(resultSet, "heading_degrees"),
                nullableDouble(resultSet, "accuracy_meters"),
                RealtimeLocationEvent.QualityStatus.valueOf(
                        resultSet.getString("quality_status")),
                resultSet.getString("quality_reason"));
    }

    private Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private Comparator<RealtimeLocationEvent> currentOrder() {
        return Comparator.comparing(RealtimeLocationEvent::occurredAt).reversed()
                .thenComparing(RealtimeLocationEvent::sourceSequence, Comparator.reverseOrder());
    }

    private long maximumSequence(List<RealtimeLocationEvent> events) {
        return events.stream().mapToLong(RealtimeLocationEvent::sourceSequence).max().orElse(0L);
    }

    private RealtimeLocationEvent readEvent(String value) {
        try {
            return jsonMapper.readValue(value, RealtimeLocationEvent.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("当前位置缓存载荷无法解析", exception);
        }
    }

    private String writeEvent(RealtimeLocationEvent event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("当前位置回填载荷无法序列化", exception);
        }
    }

    private void requireLimit(int limit, int maximum, String label) {
        if (limit < 1 || limit > maximum) {
            throw new IllegalArgumentException(label + "必须在 1 到 " + maximum + " 之间");
        }
    }

    private String valueKey(UUID siteId, UUID tagId) {
        return PREFIX + "{" + siteId + "}:tag:" + tagId;
    }

    private String indexKey(UUID siteId) {
        return PREFIX + "{" + siteId + "}:tags";
    }

    /** 当前位置仓储结果。 */
    public record CurrentResult(
            List<RealtimeLocationEvent> events,
            long watermark,
            String source,
            boolean truncated
    ) {
        /** 创建不可变当前位置结果。 */
        public CurrentResult {
            events = List.copyOf(Objects.requireNonNull(events));
        }
    }

    /** 轨迹查询对象类型。 */
    public enum SubjectType {
        /** 标签。 */
        TAG,
        /** 车辆。 */
        VEHICLE,
        /** 人员。 */
        PERSON
    }

    /**
     * 已校验并绑定固定水位和键集游标的轨迹查询。
     */
    public record TrajectoryQuery(
            UUID siteId,
            SubjectType subjectType,
            UUID subjectId,
            Instant from,
            Instant to,
            long watermark,
            Instant afterOccurredAt,
            Long afterIngestSequence
    ) {
    }

    /** 一页有硬上限的原始轨迹点。 */
    public record TrajectorySlice(
            List<LocationTrajectoryResponse.Point> points,
            boolean hasMore,
            Instant lastOccurredAt,
            Long lastIngestSequence
    ) {
        /** 创建不可变轨迹切片。 */
        public TrajectorySlice {
            points = List.copyOf(Objects.requireNonNull(points));
        }
    }
}
