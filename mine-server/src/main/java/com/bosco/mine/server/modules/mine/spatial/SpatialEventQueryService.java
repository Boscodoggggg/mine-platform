package com.bosco.mine.server.modules.mine.spatial;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.time.MineClock;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.bosco.mine.server.modules.device.infrastructure.TelemetryQueryConnection;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialEventDetailResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialEventPageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 使用组织和矿区双重作用域提供空间事件固定水位查询。
 */
@Service
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class SpatialEventQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpatialEventQueryService.class);

    private static final ErrorCode RANGE_INVALID =
            new ErrorCode("MINE-SPATIAL-EVENT-400-001", "空间事件查询时间范围不合法");
    private static final ErrorCode FILTER_INVALID =
            new ErrorCode("MINE-SPATIAL-EVENT-400-002", "空间事件查询筛选条件不合法");
    private static final ErrorCode PAGE_INVALID =
            new ErrorCode("MINE-SPATIAL-EVENT-400-003", "空间事件查询游标或水位不合法");
    private static final ErrorCode EVENT_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-EVENT-404-001", "空间事件不存在");
    private static final ErrorCode QUERY_UNAVAILABLE =
            new ErrorCode("MINE-SPATIAL-EVENT-503-001", "空间事件查询服务暂时不可用");
    private static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> ZONE_TYPES = Set.of("WAYPOINT", "GEOFENCE");
    private static final Set<String> SUBJECT_TYPES = Set.of("TAG", "VEHICLE", "PERSON");
    private static final Set<String> EVENT_TYPES = Set.of("ENTER", "EXIT", "DWELL");

    private final JdbcTemplate jdbcTemplate;
    private final MineScopeAuthorizer authorizer;
    private final MineClock clock;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 使用遥测只读连接创建生产查询服务。
     *
     * @param connection 遥测只读连接
     * @param authorizer 矿区作用域授权器
     */
    @Autowired
    public SpatialEventQueryService(
            TelemetryQueryConnection connection,
            MineScopeAuthorizer authorizer
    ) {
        this(connection.jdbcTemplate(), authorizer, MineClock.systemUtc());
    }

    /**
     * 使用显式依赖创建空间事件查询服务，供数据库集成验证使用。
     *
     * @param jdbcTemplate 遥测只读 JDBC 模板
     * @param authorizer 矿区作用域授权器
     * @param clock 业务时钟
     */
    public SpatialEventQueryService(
            JdbcTemplate jdbcTemplate,
            MineScopeAuthorizer authorizer,
            MineClock clock
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "空间事件 JDBC 模板不能为空");
        this.authorizer = Objects.requireNonNull(authorizer, "矿区授权器不能为空");
        this.clock = Objects.requireNonNull(clock, "业务时钟不能为空");
    }

    /**
     * 查询一页稳定的空间事件摘要。
     *
     * @param siteId 矿区标识
     * @param from 开始时间，包含；为空时默认过去二十四小时
     * @param to 结束时间，包含；为空时默认当前时间
     * @param watermark 首页返回的固定事件水位
     * @param cursor 下一页不透明游标
     * @param zoneType 可选区域类型
     * @param zoneId 可选稳定区域标识
     * @param subjectType 可选对象类型
     * @param subjectId 可选对象标识
     * @param eventType 可选事件类型
     * @param pageSize 每页数量
     * @return 固定水位分页结果
     */
    public SpatialEventPageResponse findEvents(
            UUID siteId,
            Instant from,
            Instant to,
            Long watermark,
            String cursor,
            String zoneType,
            UUID zoneId,
            String subjectType,
            UUID subjectId,
            String eventType,
            int pageSize
    ) {
        MineSite site = authorizer.requireSite(siteId);
        Instant resolvedTo = to == null ? clock.instant() : to;
        Instant resolvedFrom = from == null ? resolvedTo.minus(DEFAULT_RANGE) : from;
        validate(resolvedFrom, resolvedTo, watermark, cursor, zoneType, zoneId,
                subjectType, subjectId, eventType, pageSize);
        try {
            long currentWatermark = currentWatermark(site.organizationId(), siteId);
            long resolvedWatermark = watermark == null ? currentWatermark : watermark;
            if (resolvedWatermark < 0L || resolvedWatermark > currentWatermark) {
                throw new MineException(PAGE_INVALID, Map.of(
                        "watermark", resolvedWatermark,
                        "currentWatermark", currentWatermark));
            }
            CursorState state = cursor == null ? null : decodeCursor(cursor);
            QueryBinding binding = new QueryBinding(
                    site.organizationId(), siteId, resolvedFrom, resolvedTo,
                    resolvedWatermark, normalize(zoneType), zoneId,
                    normalize(subjectType), subjectId, normalize(eventType));
            if (state != null && !state.matches(binding)) {
                throw new MineException(PAGE_INVALID, Map.of("reason", "CURSOR_QUERY_MISMATCH"));
            }
            List<SpatialEventPageResponse.Item> rows = queryPage(
                    binding, state, pageSize + 1);
            boolean hasMore = rows.size() > pageSize;
            List<SpatialEventPageResponse.Item> items = hasMore
                    ? List.copyOf(rows.subList(0, pageSize)) : List.copyOf(rows);
            SpatialEventPageResponse.Item last = items.isEmpty() ? null : items.getLast();
            String nextCursor = hasMore ? encodeCursor(binding, last) : null;
            return new SpatialEventPageResponse(
                    siteId, resolvedFrom, resolvedTo, resolvedWatermark, nextCursor, items);
        } catch (MineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("空间事件分页查询失败: siteId={}", siteId, exception);
            throw new MineException(QUERY_UNAVAILABLE, Map.of("siteId", siteId));
        }
    }

    /**
     * 查询一条带有判定证据的空间事件详情。
     *
     * @param siteId 矿区标识
     * @param eventId 空间事件标识
     * @return 空间事件详情
     */
    public SpatialEventDetailResponse findEvent(UUID siteId, UUID eventId) {
        MineSite site = authorizer.requireSite(siteId);
        if (eventId == null) {
            throw new MineException(EVENT_NOT_FOUND, Map.of());
        }
        try {
            List<SpatialEventDetailResponse> events = jdbcTemplate.query("""
                    SELECT e.event_id, e.event_sequence, e.organization_id, e.site_id,
                           e.zone_type, e.zone_id, e.zone_version_id, e.tag_id,
                           e.vehicle_id, e.person_id, e.event_type,
                           e.anchor_location_event_id, e.previous_state, e.current_state,
                           e.confirmation_points, q.ingest_sequence, e.runtime_revision,
                           e.occurred_at, e.created_at, e.evidence::TEXT AS evidence
                    FROM spatial_event e
                    JOIN spatial_evaluation_queue q
                      ON q.location_event_id = e.anchor_location_event_id
                     AND q.organization_id = e.organization_id
                     AND q.site_id = e.site_id
                     AND q.tag_id = e.tag_id
                    WHERE e.event_id = ?
                      AND e.organization_id = ?
                      AND e.site_id = ?
                    """, this::mapDetail, eventId, site.organizationId(), siteId);
            if (events.isEmpty()) {
                throw new MineException(EVENT_NOT_FOUND, Map.of("eventId", eventId));
            }
            return events.getFirst();
        } catch (MineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("空间事件详情查询失败: siteId={}, eventId={}",
                    siteId, eventId, exception);
            throw new MineException(QUERY_UNAVAILABLE, Map.of("siteId", siteId));
        }
    }

    private long currentWatermark(UUID organizationId, UUID siteId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(event_sequence), 0)
                FROM spatial_event
                WHERE organization_id = ? AND site_id = ?
                """, Long.class, organizationId, siteId);
        return value == null ? 0L : value;
    }

    private List<SpatialEventPageResponse.Item> queryPage(
            QueryBinding binding,
            CursorState cursor,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.event_id, e.event_sequence, e.organization_id, e.site_id,
                       e.zone_type, e.zone_id, e.zone_version_id, e.tag_id,
                       e.vehicle_id, e.person_id, e.event_type, e.previous_state,
                       e.current_state, e.confirmation_points, q.ingest_sequence,
                       e.runtime_revision, e.occurred_at
                FROM spatial_event e
                JOIN spatial_evaluation_queue q
                  ON q.location_event_id = e.anchor_location_event_id
                 AND q.organization_id = e.organization_id
                 AND q.site_id = e.site_id
                 AND q.tag_id = e.tag_id
                WHERE e.organization_id = ? AND e.site_id = ?
                  AND e.occurred_at >= ? AND e.occurred_at <= ?
                  AND e.event_sequence <= ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(binding.organizationId());
        arguments.add(binding.siteId());
        arguments.add(Timestamp.from(binding.from()));
        arguments.add(Timestamp.from(binding.to()));
        arguments.add(binding.watermark());
        appendFilter(sql, arguments, "e.zone_type", binding.zoneType());
        appendFilter(sql, arguments, "e.zone_id", binding.zoneId());
        if (binding.subjectType() != null) {
            String column = switch (binding.subjectType()) {
                case "TAG" -> "e.tag_id";
                case "VEHICLE" -> "e.vehicle_id";
                case "PERSON" -> "e.person_id";
                default -> throw new MineException(FILTER_INVALID, Map.of());
            };
            appendFilter(sql, arguments, column, binding.subjectId());
        }
        appendFilter(sql, arguments, "e.event_type", binding.eventType());
        if (cursor != null) {
            sql.append(" AND (e.occurred_at, e.event_sequence) < (?, ?)");
            arguments.add(Timestamp.from(cursor.occurredAt()));
            arguments.add(cursor.eventSequence());
        }
        sql.append(" ORDER BY e.occurred_at DESC, e.event_sequence DESC LIMIT ?");
        arguments.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapItem, arguments.toArray());
    }

    private void appendFilter(
            StringBuilder sql,
            List<Object> arguments,
            String column,
            Object value
    ) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            arguments.add(value);
        }
    }

    private SpatialEventPageResponse.Item mapItem(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new SpatialEventPageResponse.Item(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getLong("event_sequence"),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("site_id", UUID.class),
                resultSet.getString("zone_type"),
                resultSet.getObject("zone_id", UUID.class),
                resultSet.getObject("zone_version_id", UUID.class),
                resultSet.getObject("tag_id", UUID.class),
                resultSet.getObject("vehicle_id", UUID.class),
                resultSet.getObject("person_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("previous_state"),
                resultSet.getString("current_state"),
                resultSet.getInt("confirmation_points"),
                resultSet.getLong("ingest_sequence"),
                resultSet.getLong("runtime_revision"),
                resultSet.getTimestamp("occurred_at").toInstant());
    }

    private SpatialEventDetailResponse mapDetail(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        try {
            return new SpatialEventDetailResponse(
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getLong("event_sequence"),
                    resultSet.getObject("organization_id", UUID.class),
                    resultSet.getObject("site_id", UUID.class),
                    resultSet.getString("zone_type"),
                    resultSet.getObject("zone_id", UUID.class),
                    resultSet.getObject("zone_version_id", UUID.class),
                    resultSet.getObject("tag_id", UUID.class),
                    resultSet.getObject("vehicle_id", UUID.class),
                    resultSet.getObject("person_id", UUID.class),
                    resultSet.getString("event_type"),
                    resultSet.getObject("anchor_location_event_id", UUID.class),
                    resultSet.getString("previous_state"),
                    resultSet.getString("current_state"),
                    resultSet.getInt("confirmation_points"),
                    resultSet.getLong("ingest_sequence"),
                    resultSet.getLong("runtime_revision"),
                    resultSet.getTimestamp("occurred_at").toInstant(),
                    resultSet.getTimestamp("created_at").toInstant(),
                    jsonMapper.readValue(
                            resultSet.getString("evidence"), RealtimeSpatialEvent.Evidence.class));
        } catch (JacksonException exception) {
            throw new SQLException("空间事件证据格式非法", exception);
        }
    }

    private void validate(
            Instant from,
            Instant to,
            Long watermark,
            String cursor,
            String zoneType,
            UUID zoneId,
            String subjectType,
            UUID subjectId,
            String eventType,
            int pageSize
    ) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new MineException(RANGE_INVALID, Map.of("maximum", MAX_RANGE.toString()));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE
                || invalidValue(zoneType, ZONE_TYPES)
                || invalidValue(subjectType, SUBJECT_TYPES)
                || invalidValue(eventType, EVENT_TYPES)
                || (subjectType == null) != (subjectId == null)) {
            throw new MineException(FILTER_INVALID, Map.of("maximumPageSize", MAX_PAGE_SIZE));
        }
        if (cursor != null && (cursor.isBlank() || watermark == null)) {
            throw new MineException(PAGE_INVALID, Map.of("required", "watermark"));
        }
    }

    private boolean invalidValue(String value, Set<String> allowed) {
        return value != null && !allowed.contains(normalize(value));
    }

    private String normalize(String value) {
        return value == null ? null : value.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private String encodeCursor(QueryBinding binding, SpatialEventPageResponse.Item last) {
        CursorState state = CursorState.from(binding, last);
        try {
            byte[] json = jsonMapper.writeValueAsBytes(state);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("空间事件游标编码失败", exception);
        }
    }

    private CursorState decodeCursor(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII));
            CursorState state = jsonMapper.readValue(json, CursorState.class);
            if (state.organizationId() == null || state.siteId() == null
                    || state.from() == null || state.to() == null
                    || state.watermark() < 0L || state.occurredAt() == null
                    || state.eventSequence() < 1L) {
                throw new IllegalArgumentException("游标顺序字段不合法");
            }
            return state;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new MineException(PAGE_INVALID, Map.of("reason", "CURSOR_INVALID"));
        }
    }

    /** 查询条件与水位绑定。 */
    private record QueryBinding(
            UUID organizationId,
            UUID siteId,
            Instant from,
            Instant to,
            long watermark,
            String zoneType,
            UUID zoneId,
            String subjectType,
            UUID subjectId,
            String eventType
    ) {
    }

    /** 不透明游标中绑定的完整查询条件与最后顺序键。 */
    private record CursorState(
            UUID organizationId,
            UUID siteId,
            Instant from,
            Instant to,
            long watermark,
            String zoneType,
            UUID zoneId,
            String subjectType,
            UUID subjectId,
            String eventType,
            Instant occurredAt,
            long eventSequence
    ) {

        /** 从查询和页末事件创建游标状态。 */
        private static CursorState from(
                QueryBinding binding,
                SpatialEventPageResponse.Item last
        ) {
            return new CursorState(
                    binding.organizationId(), binding.siteId(), binding.from(), binding.to(),
                    binding.watermark(), binding.zoneType(), binding.zoneId(),
                    binding.subjectType(), binding.subjectId(), binding.eventType(),
                    last.occurredAt(), last.eventSequence());
        }

        /** 判断游标是否仍属于完全相同的查询。 */
        private boolean matches(QueryBinding binding) {
            return organizationId.equals(binding.organizationId())
                    && siteId.equals(binding.siteId())
                    && from.equals(binding.from())
                    && to.equals(binding.to())
                    && watermark == binding.watermark()
                    && Objects.equals(zoneType, binding.zoneType())
                    && Objects.equals(zoneId, binding.zoneId())
                    && Objects.equals(subjectType, binding.subjectType())
                    && Objects.equals(subjectId, binding.subjectId())
                    && Objects.equals(eventType, binding.eventType());
        }
    }
}
