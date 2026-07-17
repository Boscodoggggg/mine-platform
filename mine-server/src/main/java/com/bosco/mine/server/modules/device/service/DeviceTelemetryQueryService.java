package com.bosco.mine.server.modules.device.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.device.dto.DeviceLatestResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryHistoryPointResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryHistoryResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryLatestItemResponse;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.Aggregation;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistoryPage;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistoryQuery;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistoryWatermarks;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.TelemetryValue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 提供设备最新遥测和历史遥测只读查询。
 */
@Service
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class DeviceTelemetryQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            DeviceTelemetryQueryService.class
    );

    private static final ErrorCode DEVICE_NOT_FOUND =
            new ErrorCode("DEVICE-404-002", "设备不存在");
    private static final ErrorCode TIME_RANGE_INVALID =
            new ErrorCode("TELEMETRY-400-001", "遥测查询时间范围不合法");
    private static final ErrorCode AGGREGATION_INVALID =
            new ErrorCode("TELEMETRY-400-002", "遥测聚合参数不合法");
    private static final ErrorCode PROPERTY_INVALID =
            new ErrorCode("TELEMETRY-400-003", "遥测属性参数不合法");
    private static final ErrorCode TELEMETRY_UNAVAILABLE =
            new ErrorCode("TELEMETRY-503-001", "遥测查询服务暂时不可用");
    private static final Pattern PROPERTY_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    private static final Duration RAW_MAX_RANGE = Duration.ofDays(7);
    private static final Duration AGGREGATE_MAX_RANGE = Duration.ofDays(366);
    private static final Duration MIN_BUCKET = Duration.ofMinutes(1);
    private static final Duration MAX_BUCKET = Duration.ofHours(24);
    private static final long MAX_BUCKET_COUNT = 10_000L;
    private static final int MAX_OFFSET = 1_000_000;
    private static final long BACKFILL_WAIT_SECONDS = 2L;

    private final DeviceMapper deviceMapper;
    private final DeviceTelemetryRepository repository;
    private final ConcurrentMap<LatestKey, CompletableFuture<Optional<LatestResolution>>> inFlight =
            new ConcurrentHashMap<>();

    /**
     * 创建设备遥测查询服务。
     *
     * @param deviceMapper 设备台账访问接口
     * @param repository 遥测查询仓储
     */
    public DeviceTelemetryQueryService(
            DeviceMapper deviceMapper,
            DeviceTelemetryRepository repository
    ) {
        this.deviceMapper = deviceMapper;
        this.repository = repository;
    }

    /**
     * 查询设备指定属性的最新值。
     *
     * @param deviceId 设备标识
     * @param propertyIds 属性标识集合
     * @return 最新值快照
     */
    public DeviceLatestResponse findLatest(UUID deviceId, List<String> propertyIds) {
        requireDevice(deviceId);
        List<String> normalizedProperties = normalizeProperties(propertyIds);
        Map<String, LatestResolution> resolved = new LinkedHashMap<>();
        Map<String, LatestClaim> claims = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        boolean cacheAvailable = true;
        for (String propertyId : normalizedProperties) {
            Optional<TelemetryValue> cached = Optional.empty();
            if (cacheAvailable) {
                try {
                    cached = repository.findCachedLatest(deviceId, propertyId);
                } catch (DataAccessException exception) {
                    cacheAvailable = false;
                    LOGGER.warn("Redis 最新值读取失败，降级查询持久最新值，deviceId={}", deviceId);
                }
            }
            if (cached.isPresent()) {
                resolved.put(propertyId, new LatestResolution(cached.orElseThrow(), "REDIS"));
                continue;
            }
            claims.put(propertyId, claimLatest(deviceId, propertyId));
        }
        loadOwnedLatest(deviceId, claims.values().stream()
                .filter(LatestClaim::owner)
                .toList());
        List<TelemetryLatestItemResponse> items = new ArrayList<>(normalizedProperties.size());
        for (String propertyId : normalizedProperties) {
            LatestResolution cached = resolved.get(propertyId);
            if (cached != null) {
                items.add(toLatestResponse(cached.value(), cached.source()));
                continue;
            }
            Optional<LatestResolution> backfilled = awaitBackfill(
                    claims.get(propertyId).future(), claims.get(propertyId).key()
            );
            if (backfilled.isPresent()) {
                LatestResolution resolution = backfilled.orElseThrow();
                items.add(toLatestResponse(resolution.value(), resolution.source()));
            } else {
                missing.add(propertyId);
            }
        }
        return new DeviceLatestResponse(deviceId, items, missing);
    }

    /**
     * 分页查询设备单属性历史。
     *
     * @param deviceId 设备标识
     * @param propertyId 属性标识
     * @param from 开始时间，包含
     * @param to 结束时间，不包含
     * @param aggregation 聚合方式
     * @param bucket 聚合时间桶
     * @param watermark 分页摄取水位；为空时读取当前已提交水位
     * @param rollupWatermark 分页聚合版本水位；为空时与摄取水位一同生成
     * @param page 页码
     * @param size 每页数量
     * @return 历史分页结果
     */
    public TelemetryHistoryResponse findHistory(
            UUID deviceId,
            String propertyId,
            Instant from,
            Instant to,
            String aggregation,
            Duration bucket,
            Long watermark,
            Long rollupWatermark,
            int page,
            int size
    ) {
        requireDevice(deviceId);
        requireProperty(propertyId);
        Aggregation resolvedAggregation = resolveAggregation(aggregation);
        validateHistoryRange(from, to, resolvedAggregation, bucket);
        if ((watermark == null) != (rollupWatermark == null)
                || (watermark != null && watermark < 0)
                || (rollupWatermark != null && rollupWatermark < 0)) {
            throw new MineException(TIME_RANGE_INVALID, Map.of(
                    "watermark", String.valueOf(watermark),
                    "rollupWatermark", String.valueOf(rollupWatermark)
            ));
        }
        if (page < 1 || size < 1 || size > 1000) {
            throw new MineException(TIME_RANGE_INVALID, Map.of("page", page, "size", size));
        }
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException exception) {
            throw new MineException(TIME_RANGE_INVALID, Map.of("page", page, "size", size));
        }
        if (offset > MAX_OFFSET) {
            throw new MineException(TIME_RANGE_INVALID, Map.of(
                    "page", page, "size", size, "maxOffset", MAX_OFFSET
            ));
        }
        if (page > 1 && watermark == null) {
            throw new MineException(TIME_RANGE_INVALID, Map.of(
                    "page", page,
                    "requiredWatermarks", List.of("watermark", "rollupWatermark")
            ));
        }
        HistoryPage result;
        long resolvedWatermark;
        long resolvedRollupWatermark;
        try {
            HistoryWatermarks current = repository.currentHistoryWatermarks();
            resolvedWatermark = watermark == null
                    ? current.ingestionWatermark() : watermark;
            resolvedRollupWatermark = rollupWatermark == null
                    ? current.rollupWatermark() : rollupWatermark;
            if (resolvedWatermark > current.ingestionWatermark()
                    || resolvedRollupWatermark > current.rollupWatermark()) {
                throw new MineException(TIME_RANGE_INVALID, Map.of(
                        "watermark", resolvedWatermark,
                        "currentWatermark", current.ingestionWatermark(),
                        "rollupWatermark", resolvedRollupWatermark,
                        "currentRollupWatermark", current.rollupWatermark()
                ));
            }
            result = repository.findHistory(new HistoryQuery(
                    deviceId,
                    propertyId,
                    from,
                    to,
                    resolvedAggregation,
                    resolvedAggregation == Aggregation.RAW ? null : bucket,
                    resolvedWatermark,
                    resolvedRollupWatermark,
                    offset,
                    size
            ));
        } catch (QueryTimeoutException | DataAccessResourceFailureException exception) {
            throw telemetryUnavailable(deviceId, propertyId);
        }
        List<TelemetryHistoryPointResponse> items = result.items().stream()
                .map(point -> new TelemetryHistoryPointResponse(
                        point.occurredAt(),
                        point.receivedAt(),
                        point.value(),
                        point.quality(),
                        point.eventId(),
                        point.sampleCount(),
                        point.source().name()
                ))
                .toList();
        return new TelemetryHistoryResponse(
                deviceId,
                propertyId,
                resolvedAggregation.name(),
                resolvedAggregation == Aggregation.RAW ? null : bucket,
                resolvedWatermark,
                resolvedRollupWatermark,
                items,
                page,
                size,
                result.total()
        );
    }

    private void requireDevice(UUID deviceId) {
        if (deviceId == null || deviceMapper.findDeviceById(deviceId).isEmpty()) {
            throw new MineException(DEVICE_NOT_FOUND, Map.of("deviceId", String.valueOf(deviceId)));
        }
    }

    private List<String> normalizeProperties(List<String> propertyIds) {
        if (propertyIds == null || propertyIds.isEmpty() || propertyIds.size() > 100) {
            throw new MineException(PROPERTY_INVALID, Map.of());
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String propertyId : propertyIds) {
            requireProperty(propertyId);
            unique.add(propertyId);
        }
        return List.copyOf(unique);
    }

    private void requireProperty(String propertyId) {
        if (propertyId == null || !PROPERTY_PATTERN.matcher(propertyId).matches()) {
            throw new MineException(PROPERTY_INVALID, Map.of(
                    "propertyId", String.valueOf(propertyId)
            ));
        }
    }

    private Aggregation resolveAggregation(String aggregation) {
        try {
            return Aggregation.valueOf(aggregation == null ? "RAW" : aggregation);
        } catch (IllegalArgumentException exception) {
            throw new MineException(AGGREGATION_INVALID, Map.of(
                    "aggregation", String.valueOf(aggregation)
            ));
        }
    }

    private void validateHistoryRange(
            Instant from,
            Instant to,
            Aggregation aggregation,
            Duration bucket
    ) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new MineException(TIME_RANGE_INVALID, Map.of());
        }
        Duration range = Duration.between(from, to);
        if (aggregation == Aggregation.RAW) {
            if (range.compareTo(RAW_MAX_RANGE) > 0 || bucket != null) {
                throw new MineException(TIME_RANGE_INVALID, Map.of(
                        "maxRange", RAW_MAX_RANGE.toString()
                ));
            }
            return;
        }
        if (range.compareTo(AGGREGATE_MAX_RANGE) > 0
                || bucket == null
                || bucket.compareTo(MIN_BUCKET) < 0
                || bucket.compareTo(MAX_BUCKET) > 0
                || bucket.getNano() != 0
                || divideCeil(range.toMillis(), bucket.toMillis()) > MAX_BUCKET_COUNT) {
            throw new MineException(AGGREGATION_INVALID, Map.of(
                    "minBucket", MIN_BUCKET.toString(),
                    "maxBucket", MAX_BUCKET.toString()
            ));
        }
    }

    private long divideCeil(long dividend, long divisor) {
        return 1L + (dividend - 1L) / divisor;
    }

    private LatestClaim claimLatest(UUID deviceId, String propertyId) {
        LatestKey key = new LatestKey(deviceId, propertyId);
        CompletableFuture<Optional<LatestResolution>> owned = new CompletableFuture<>();
        CompletableFuture<Optional<LatestResolution>> existing = inFlight.putIfAbsent(key, owned);
        if (existing != null) {
            return new LatestClaim(key, existing, false);
        }
        return new LatestClaim(key, owned, true);
    }

    private void loadOwnedLatest(UUID deviceId, List<LatestClaim> ownedClaims) {
        if (ownedClaims.isEmpty()) {
            return;
        }
        try {
            List<String> properties = ownedClaims.stream()
                    .map(claim -> claim.key().propertyId())
                    .toList();
            Map<String, TelemetryValue> loaded = repository.findLatestHistory(
                    deviceId, properties
            );
            boolean cacheAvailable = true;
            if (!loaded.isEmpty()) {
                try {
                    repository.restoreLatestState(deviceId);
                } catch (DataAccessException exception) {
                    cacheAvailable = false;
                    LOGGER.warn("Redis 顺序状态恢复失败，本次直接返回持久最新值，deviceId={}", deviceId);
                }
            }
            for (LatestClaim claim : ownedClaims) {
                TelemetryValue value = loaded.get(claim.key().propertyId());
                Optional<LatestResolution> resolution = value == null
                        ? Optional.empty()
                        : Optional.of(cacheAvailable
                        ? reconcileBackfill(deviceId, value)
                        : new LatestResolution(value, "TIMESCALEDB"));
                claim.future().complete(resolution);
            }
        } catch (QueryTimeoutException | DataAccessResourceFailureException exception) {
            MineException timeout = telemetryUnavailable(ownedClaims.getFirst().key());
            ownedClaims.forEach(claim -> claim.future().completeExceptionally(timeout));
        } catch (RuntimeException exception) {
            ownedClaims.forEach(claim -> claim.future().completeExceptionally(exception));
        } finally {
            ownedClaims.forEach(claim -> inFlight.remove(claim.key(), claim.future()));
        }
    }

    private LatestResolution reconcileBackfill(UUID deviceId, TelemetryValue loaded) {
        try {
            boolean inserted = repository.cacheLatestIfAbsent(deviceId, loaded);
            Optional<TelemetryValue> finalCached = repository.findCachedLatest(
                    deviceId, loaded.propertyId()
            );
            if (finalCached.isEmpty()) {
                return new LatestResolution(loaded, "TIMESCALEDB");
            }
            TelemetryValue winner = finalCached.orElseThrow();
            String source = inserted && winner.equals(loaded) ? "TIMESCALEDB" : "REDIS";
            return new LatestResolution(winner, source);
        } catch (DataAccessException exception) {
            LOGGER.warn("Redis 最新值回填失败，本次直接返回持久最新值，deviceId={}，propertyId={}",
                    deviceId, loaded.propertyId());
            return new LatestResolution(loaded, "TIMESCALEDB");
        }
    }

    private Optional<LatestResolution> awaitBackfill(
            CompletableFuture<Optional<LatestResolution>> future,
            LatestKey key
    ) {
        try {
            return future.get(BACKFILL_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw telemetryUnavailable(key);
        } catch (TimeoutException exception) {
            throw telemetryUnavailable(key);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("遥测最新值回源失败", exception.getCause());
        }
    }

    private MineException telemetryUnavailable(LatestKey key) {
        return telemetryUnavailable(key.deviceId(), key.propertyId());
    }

    private MineException telemetryUnavailable(UUID deviceId, String propertyId) {
        return new MineException(TELEMETRY_UNAVAILABLE, Map.of(
                "deviceId", deviceId, "propertyId", propertyId
        ));
    }

    private TelemetryLatestItemResponse toLatestResponse(TelemetryValue value, String source) {
        return new TelemetryLatestItemResponse(
                value.propertyId(),
                value.value(),
                value.occurredAt(),
                value.receivedAt(),
                value.quality(),
                value.eventId(),
                value.bootId(),
                value.sequence(),
                source
        );
    }

    private record LatestKey(UUID deviceId, String propertyId) {
    }

    private record LatestResolution(TelemetryValue value, String source) {
    }

    private record LatestClaim(
            LatestKey key,
            CompletableFuture<Optional<LatestResolution>> future,
            boolean owner
    ) {
    }
}
