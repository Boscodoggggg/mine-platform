package com.bosco.mine.telemetry.location;

import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 以矿区为 Redis Cluster 哈希槽原子保存标签当前位置，并可从可靠实时 Outbox 重建。
 */
public final class LocationLatestCache {

    private static final String PREFIX = "mine:location:";
    private static final DefaultRedisScript<Long> UPDATE_IF_NEWER = new DefaultRedisScript<>("""
            local currentQuality = redis.call('HGET', KEYS[1], 'quality')
            local currentOccurredAt = tonumber(redis.call('HGET', KEYS[1], 'occurredAt'))
            local currentSequence = tonumber(redis.call('HGET', KEYS[1], 'ingestSequence'))
            local incomingQuality = ARGV[1]
            local incomingOccurredAt = tonumber(ARGV[2])
            local incomingSequence = tonumber(ARGV[3])

            if currentQuality == 'GOOD' and incomingQuality == 'DEGRADED' then
                return 0
            end
            if currentOccurredAt then
                if incomingOccurredAt < currentOccurredAt then
                    return 0
                end
                if incomingOccurredAt == currentOccurredAt
                        and incomingSequence <= currentSequence then
                    return 0
                end
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
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建可从 TimescaleDB 可靠实时事件重建的当前位置缓存。
     *
     * @param redisTemplate Redis 字符串操作模板
     * @param dataSource Telemetry 时序数据库
     */
    public LocationLatestCache(StringRedisTemplate redisTemplate, DataSource dataSource) {
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate, "定位 Redis 操作模板不能为空");
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(
                dataSource, "定位缓存重建数据源不能为空"));
    }

    /**
     * 仅当位置事实更新且不会用降级值覆盖正常值时原子刷新缓存。
     *
     * @param event 已提交的位置实时事件
     * @return 实际完成更新时为真
     */
    public boolean updateIfNewer(RealtimeLocationEvent event) {
        Objects.requireNonNull(event, "定位实时事件不能为空");
        Long updated = redisTemplate.execute(
                UPDATE_IF_NEWER,
                List.of(valueKey(event.siteId(), event.tagId()), indexKey(event.siteId())),
                event.qualityStatus().name(),
                Long.toString(event.occurredAt().toEpochMilli()),
                Long.toString(event.sourceSequence()),
                write(event),
                event.tagId().toString());
        return Long.valueOf(1L).equals(updated);
    }

    /**
     * 读取单个标签的当前位置。
     *
     * @param siteId 矿区标识
     * @param tagId 标签标识
     * @return 缓存存在时返回强类型实时事件
     */
    public Optional<RealtimeLocationEvent> find(UUID siteId, UUID tagId) {
        Object payload = redisTemplate.opsForHash().get(valueKey(siteId, tagId), "payload");
        if (payload == null) {
            return Optional.empty();
        }
        return Optional.of(read(payload.toString()));
    }

    /**
     * 从可靠 Outbox 为一个矿区重建当前位置；同一标签优先选择最新正常事实。
     *
     * @param siteId 矿区标识
     * @param limit 单次最多重建标签数
     * @return 本次读取的标签数量
     */
    public int rebuildSite(UUID siteId, int limit) {
        Objects.requireNonNull(siteId, "重建矿区标识不能为空");
        requireLimit(limit);
        List<RealtimeLocationEvent> events = jdbcTemplate.query("""
                SELECT candidate.payload::TEXT
                FROM (
                    SELECT DISTINCT ON (payload->>'tagId') payload, ingest_sequence,
                           payload->>'tagId' AS tag_id,
                           payload->>'qualityStatus' AS quality_status,
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
                """, (resultSet, rowNumber) -> read(resultSet.getString(1)),
                siteId.toString(), limit);
        events.forEach(this::updateIfNewer);
        if (!events.isEmpty()) {
            RealtimeLocationEvent maximum = events.stream()
                    .max(java.util.Comparator.comparingLong(
                            RealtimeLocationEvent::sourceSequence))
                    .orElseThrow();
            jdbcTemplate.update("""
                    INSERT INTO location_current_rebuild_watermark (
                        site_id, organization_id, last_ingest_sequence,
                        last_event_id, rebuilt_at, updated_at
                    ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (site_id) DO UPDATE SET
                        organization_id = EXCLUDED.organization_id,
                        last_ingest_sequence = GREATEST(
                            location_current_rebuild_watermark.last_ingest_sequence,
                            EXCLUDED.last_ingest_sequence),
                        last_event_id = CASE
                            WHEN EXCLUDED.last_ingest_sequence >=
                                 location_current_rebuild_watermark.last_ingest_sequence
                            THEN EXCLUDED.last_event_id
                            ELSE location_current_rebuild_watermark.last_event_id
                        END,
                        rebuilt_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    """, maximum.siteId(), maximum.organizationId(),
                    maximum.sourceSequence(), maximum.eventId());
        }
        return events.size();
    }

    /**
     * 有界发现有历史事实的矿区并逐矿重建。
     *
     * @param siteLimit 单次最多处理矿区数
     * @param tagLimitPerSite 每矿最多处理标签数
     * @return 本次读取的位置数量
     */
    public int rebuildBounded(int siteLimit, int tagLimitPerSite) {
        requireLimit(siteLimit);
        requireLimit(tagLimitPerSite);
        List<UUID> siteIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT (payload->>'siteId')::UUID AS site_id
                FROM location_realtime_outbox
                ORDER BY site_id
                LIMIT ?
                """, UUID.class, siteLimit);
        return siteIds.stream().mapToInt(siteId -> rebuildSite(siteId, tagLimitPerSite)).sum();
    }

    private void requireLimit(int limit) {
        if (limit < 1 || limit > 100_000) {
            throw new IllegalArgumentException("定位缓存重建上限必须在 1 到 100000 之间");
        }
    }

    private String write(RealtimeLocationEvent event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("定位当前位置无法序列化", exception);
        }
    }

    private RealtimeLocationEvent read(String value) {
        try {
            return jsonMapper.readValue(value, RealtimeLocationEvent.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("定位当前位置无法反序列化", exception);
        }
    }

    private String valueKey(UUID siteId, UUID tagId) {
        Objects.requireNonNull(siteId, "矿区标识不能为空");
        Objects.requireNonNull(tagId, "标签标识不能为空");
        return PREFIX + "{" + siteId + "}:tag:" + tagId;
    }

    private String indexKey(UUID siteId) {
        return PREFIX + "{" + siteId + "}:tags";
    }
}
