package com.bosco.mine.server.modules.mine.spatial;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.common.time.MineClock;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialReplayRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialReplayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 登记异步空间重放任务，冻结 Telemetry 当前已激活区域快照，
 * 并以事务级锁保证幂等和重叠窗口互斥。
 */
@Service
@ConditionalOnProperty(prefix = "mine.spatial-replay", name = "enabled", havingValue = "true")
public class SpatialReplayService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpatialReplayService.class);

    private static final ErrorCode REQUEST_INVALID =
            new ErrorCode("MINE-SPATIAL-REPLAY-400-001", "空间重放请求不合法");
    private static final ErrorCode VERSION_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-REPLAY-404-001", "空间重放区域版本未在定位运行快照激活");
    private static final ErrorCode IDEMPOTENCY_CONFLICT =
            new ErrorCode("MINE-SPATIAL-REPLAY-409-001", "空间重放幂等键已绑定其他请求");
    private static final ErrorCode OVERLAP_CONFLICT =
            new ErrorCode("MINE-SPATIAL-REPLAY-409-002", "相同区域版本存在重叠的未完成重放");
    private static final ErrorCode REPLAY_UNAVAILABLE =
            new ErrorCode("MINE-SPATIAL-REPLAY-503-001", "空间重放登记服务暂时不可用");
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final Set<String> ZONE_TYPES = Set.of("WAYPOINT", "GEOFENCE");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;
    private final MineScopeAuthorizer authorizer;
    private final MineClock clock;
    private final IdGenerator idGenerator;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 使用独立最小权限连接创建生产重放登记服务。
     *
     * @param connection 空间重放专用连接
     * @param authorizer 矿区作用域授权器
     */
    @Autowired
    public SpatialReplayService(
            SpatialReplayConnection connection,
            MineScopeAuthorizer authorizer
    ) {
        this(connection.dataSource(), authorizer,
                MineClock.systemUtc(), IdGenerator.timeOrdered());
    }

    /**
     * 使用显式数据源创建重放登记服务，供真实数据库集成验证使用。
     *
     * @param dataSource 重放登记数据源
     * @param authorizer 矿区作用域授权器
     * @param clock 业务时钟
     * @param idGenerator UUIDv7 标识生成器
     */
    public SpatialReplayService(
            DataSource dataSource,
            MineScopeAuthorizer authorizer,
            MineClock clock,
            IdGenerator idGenerator
    ) {
        DataSource requiredSource = Objects.requireNonNull(dataSource, "空间重放数据源不能为空");
        this.jdbcTemplate = new JdbcTemplate(requiredSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(requiredSource));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.authorizer = Objects.requireNonNull(authorizer, "矿区授权器不能为空");
        this.clock = Objects.requireNonNull(clock, "业务时钟不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "标识生成器不能为空");
    }

    /**
     * 幂等登记一个异步空间历史重放任务，不在请求线程扫描历史位置事实。
     *
     * @param siteId 矿区标识
     * @param idempotencyKey HTTP 幂等键
     * @param request 重放范围和原因
     * @return 新建或此前已登记的同一任务
     */
    public SpatialReplayResponse create(
            UUID siteId,
            String idempotencyKey,
            SpatialReplayRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        UUID userId = authorizer.currentUserId();
        NormalizedRequest normalized = normalize(idempotencyKey, request);
        validate(normalized);
        try {
            return Objects.requireNonNull(transaction.execute(status -> createLocked(
                    site.organizationId(), siteId, userId, normalized)),
                    "空间重放事务不得返回空结果");
        } catch (MineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("空间重放登记失败: siteId={}, zoneVersionId={}",
                    siteId, normalized.zoneVersionId(), exception);
            throw new MineException(REPLAY_UNAVAILABLE, Map.of("siteId", siteId));
        }
    }

    private SpatialReplayResponse createLocked(
            UUID organizationId,
            UUID siteId,
            UUID userId,
            NormalizedRequest request
    ) {
        lock("spatial-replay-idempotency:" + organizationId + ':' + siteId + ':'
                + request.idempotencyKey());
        ExistingReplay existing = findByIdempotencyKey(
                organizationId, siteId, request.idempotencyKey());
        if (existing != null) {
            if (!existing.matches(userId, request)) {
                throw new MineException(IDEMPOTENCY_CONFLICT, Map.of(
                        "idempotencyKey", request.idempotencyKey()));
            }
            return existing.response();
        }

        lock("spatial-replay-zone:" + organizationId + ':' + siteId + ':'
                + request.zoneType() + ':' + request.zoneVersionId());
        if (hasOverlappingUnfinished(organizationId, siteId, request)) {
            throw new MineException(OVERLAP_CONFLICT, Map.of(
                    "zoneVersionId", request.zoneVersionId()));
        }

        ActiveZoneSnapshot zoneSnapshot = requireActiveZoneSnapshot(
                organizationId, siteId, request.zoneType(), request.zoneVersionId());

        UUID requestId = idGenerator.next();
        Instant now = clock.instant();
        jdbcTemplate.update("""
                INSERT INTO spatial_replay_request (
                    request_id, organization_id, site_id, zone_type, zone_version_id,
                    requested_by, idempotency_key, reason, window_start, window_end,
                    status, next_attempt_at, scanned_points, emitted_events, created_at,
                    zone_item_type, zone_content, zone_content_hash, zone_runtime_revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 0, 0, ?,
                          ?, CAST(? AS JSONB), ?, ?)
                """, requestId, organizationId, siteId, request.zoneType(),
                request.zoneVersionId(), userId, request.idempotencyKey(), request.reason(),
                Timestamp.from(request.from()), Timestamp.from(request.to()),
                Timestamp.from(now), Timestamp.from(now), zoneSnapshot.itemType(),
                zoneSnapshot.contentJson(), zoneSnapshot.contentHash(),
                zoneSnapshot.runtimeRevision());
        return new SpatialReplayResponse(
                requestId, siteId, request.zoneType(), request.zoneVersionId(),
                request.from(), request.to(), "PENDING", now);
    }

    private void lock(String key) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> { }, key);
    }

    private ExistingReplay findByIdempotencyKey(
            UUID organizationId,
            UUID siteId,
            String idempotencyKey
    ) {
        List<ExistingReplay> rows = jdbcTemplate.query("""
                SELECT request_id, site_id, zone_type, zone_version_id, requested_by,
                       idempotency_key, reason, window_start, window_end, status, created_at
                FROM spatial_replay_request
                WHERE organization_id = ? AND site_id = ? AND idempotency_key = ?
                """, this::mapExisting, organizationId, siteId, idempotencyKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean hasOverlappingUnfinished(
            UUID organizationId,
            UUID siteId,
            NormalizedRequest request
    ) {
        Boolean value = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM spatial_replay_request
                    WHERE organization_id = ? AND site_id = ?
                      AND zone_type = ? AND zone_version_id = ?
                      AND status IN ('PENDING', 'PROCESSING', 'FAILED')
                      AND window_start < ? AND window_end > ?
                )
                """, Boolean.class, organizationId, siteId, request.zoneType(),
                request.zoneVersionId(), Timestamp.from(request.to()),
                Timestamp.from(request.from()));
        return Boolean.TRUE.equals(value);
    }

    /**
     * 读取并验证 Telemetry 当前已激活快照中的唯一区域版本。
     *
     * @param organizationId 组织标识
     * @param siteId 矿区标识
     * @param zoneType 区域类型
     * @param zoneVersionId 区域版本标识
     * @return 已规范化且摘要一致的激活快照
     */
    private ActiveZoneSnapshot requireActiveZoneSnapshot(
            UUID organizationId,
            UUID siteId,
            String zoneType,
            UUID zoneVersionId
    ) {
        String itemType = zoneType + "_VERSION";
        List<ActiveZoneSnapshot> snapshots = jdbcTemplate.query("""
                SELECT item_type, content_json::TEXT, content_hash, runtime_revision
                FROM spatial_replay_active_zone_snapshot
                WHERE organization_id = ? AND site_id = ?
                  AND item_key = ? AND item_type = ?
                """, (resultSet, rowNumber) -> new ActiveZoneSnapshot(
                resultSet.getString("item_type"), resultSet.getString("content_json"),
                resultSet.getString("content_hash"),
                resultSet.getLong("runtime_revision")), organizationId, siteId,
                itemType + ':' + zoneVersionId, itemType);
        if (snapshots.size() != 1) {
            throw new MineException(VERSION_NOT_FOUND, Map.of("zoneVersionId", zoneVersionId));
        }
        ActiveZoneSnapshot source = snapshots.getFirst();
        String canonicalContent = canonicalZoneJson(source.contentJson(), zoneVersionId);
        if (!hash(canonicalContent).equals(source.contentHash())) {
            throw new IllegalStateException("空间重放激活区域快照摘要不一致");
        }
        return new ActiveZoneSnapshot(
                source.itemType(), canonicalContent,
                source.contentHash(), source.runtimeRevision());
    }

    private String canonicalZoneJson(String contentJson, UUID zoneVersionId) {
        try {
            JsonNode tree = jsonMapper.readTree(contentJson);
            JsonNode version = tree == null ? null : tree.get("versionId");
            if (tree == null || !tree.isObject() || version == null || !version.isString()
                    || !zoneVersionId.equals(UUID.fromString(version.asString()))) {
                throw new IllegalStateException("空间重放激活区域快照身份不一致");
            }
            Object value = jsonMapper.treeToValue(tree, Object.class);
            return jsonMapper.writeValueAsString(canonicalize(value));
        } catch (JacksonException exception) {
            throw new IllegalStateException("空间重放激活区域快照无法规范化", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    private ExistingReplay mapExisting(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExistingReplay(
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("site_id", UUID.class),
                resultSet.getString("zone_type"),
                resultSet.getObject("zone_version_id", UUID.class),
                resultSet.getObject("requested_by", UUID.class),
                resultSet.getString("idempotency_key"),
                resultSet.getString("reason"),
                resultSet.getTimestamp("window_start").toInstant(),
                resultSet.getTimestamp("window_end").toInstant(),
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private NormalizedRequest normalize(String idempotencyKey, SpatialReplayRequest request) {
        if (request == null) {
            throw new MineException(REQUEST_INVALID, Map.of());
        }
        return new NormalizedRequest(
                idempotencyKey == null ? null : idempotencyKey.strip(),
                request.zoneType() == null ? null
                        : request.zoneType().strip().toUpperCase(Locale.ROOT),
                request.zoneVersionId(), request.from(), request.to(),
                request.reason() == null ? null : request.reason().strip());
    }

    private void validate(NormalizedRequest request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isEmpty()
                || request.idempotencyKey().length() > 128
                || !ZONE_TYPES.contains(request.zoneType())
                || request.zoneVersionId() == null
                || request.from() == null || request.to() == null
                || !request.from().isBefore(request.to())
                || Duration.between(request.from(), request.to()).compareTo(MAX_RANGE) > 0
                || request.to().isAfter(clock.instant())
                || request.reason() == null || request.reason().isEmpty()
                || request.reason().length() > 1000) {
            throw new MineException(REQUEST_INVALID, Map.of("maximumRange", MAX_RANGE.toString()));
        }
    }

    /** 已规范化且可安全写入数据库的请求。 */
    private record NormalizedRequest(
            String idempotencyKey,
            String zoneType,
            UUID zoneVersionId,
            Instant from,
            Instant to,
            String reason
    ) {
    }

    /** 已激活且可冻结到重放请求的 Telemetry 区域快照。 */
    private record ActiveZoneSnapshot(
            String itemType,
            String contentJson,
            String contentHash,
            long runtimeRevision
    ) {
    }

    /** 已登记任务的幂等比较视图。 */
    private record ExistingReplay(
            UUID requestId,
            UUID siteId,
            String zoneType,
            UUID zoneVersionId,
            UUID requestedBy,
            String idempotencyKey,
            String reason,
            Instant from,
            Instant to,
            String status,
            Instant createdAt
    ) {

        /** 判断已登记任务是否与当前用户和规范化载荷完全一致。 */
        private boolean matches(UUID userId, NormalizedRequest request) {
            return requestedBy.equals(userId)
                    && idempotencyKey.equals(request.idempotencyKey())
                    && zoneType.equals(request.zoneType())
                    && zoneVersionId.equals(request.zoneVersionId())
                    && reason.equals(request.reason())
                    && from.equals(request.from())
                    && to.equals(request.to());
        }

        /** 转换为对外响应。 */
        private SpatialReplayResponse response() {
            return new SpatialReplayResponse(
                    requestId, siteId, zoneType, zoneVersionId,
                    from, to, status, createdAt);
        }
    }
}
