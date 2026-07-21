package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.model.strategy.EffectiveStrategyResolver;
import com.bosco.mine.model.strategy.OnlineStatusStrategyDefinition;
import com.bosco.mine.model.strategy.ScopedStrategyFragment;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 批量解析设备在线状态有效策略，并提供有界的本机最后已知良好缓存。
 *
 * <p>缓存失效只在控制面事务提交后执行。刷新失败时，仅已有有效策略的设备可继续使用
 * 上一份已知良好值；从未成功解析过策略的设备会直接暴露稳定错误，避免静默回退。</p>
 */
@Service
public class EffectiveOnlineStatusPolicyProvider implements EffectiveStrategyCacheInvalidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(EffectiveOnlineStatusPolicyProvider.class);
    private static final ErrorCode EFFECTIVE_STRATEGY_INVALID =
            new ErrorCode("STRATEGY-500-001", "已发布在线状态策略无法安全解析");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String STRATEGY_TYPE = OnlineStatusStrategyDefinition.TYPE;
    private static final int QUERY_BATCH_SIZE = 1_000;
    private static final long RETRY_BACKOFF_NANOS = Duration.ofSeconds(5).toNanos();

    private final StrategyConfigurationMapper mapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final int maximumCacheSize;
    private final long cacheTtlNanos;
    private final EffectiveStrategyResolver resolver = new EffectiveStrategyResolver();
    private final OnlineStatusStrategyDefinition definition = new OnlineStatusStrategyDefinition();
    private final Object cacheMonitor = new Object();
    private final LinkedHashMap<UUID, CachedPolicy> cache =
            new LinkedHashMap<>(128, 0.75F, true);
    private final Set<UUID> staleDeviceIds = new HashSet<>();
    private final Map<UUID, Long> retryAfterNanos = new HashMap<>();

    /**
     * 创建可按部署规模调节容量和跨节点收敛时间的在线状态策略提供器。
     *
     * @param mapper        策略查询数据接口
     * @param objectMapper  JSON 解析器
     * @param meterRegistry 指标注册表
     * @param maximumCacheSize 最大缓存设备数
     * @param cacheTtl 本机缓存最长有效时间
     */
    @Autowired
    public EffectiveOnlineStatusPolicyProvider(
            StrategyConfigurationMapper mapper,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${mine.strategy.cache.maximum-size:10000}") int maximumCacheSize,
            @Value("${mine.strategy.cache.ttl:30s}") Duration cacheTtl
    ) {
        if (maximumCacheSize < 1) {
            throw new IllegalArgumentException("在线状态策略缓存容量必须大于零");
        }
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("在线状态策略缓存有效时间必须大于零");
        }
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.maximumCacheSize = maximumCacheSize;
        this.cacheTtlNanos = cacheTtl.toNanos();
    }

    /**
     * 创建可配置缓存容量的在线状态策略提供器。
     *
     * @param mapper           策略查询数据接口
     * @param objectMapper     JSON 解析器
     * @param meterRegistry    指标注册表
     * @param maximumCacheSize 最大缓存设备数
     */
    EffectiveOnlineStatusPolicyProvider(
            StrategyConfigurationMapper mapper,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            int maximumCacheSize
    ) {
        this(mapper, objectMapper, meterRegistry, maximumCacheSize, Duration.ofSeconds(30));
    }

    /**
     * 批量解析设备离线阈值。返回结果缺少设备时，调用方应兼容回退到接入配置阈值。
     *
     * @param deviceIds 设备标识集合
     * @return 已发布有效策略中的设备阈值
     */
    public Map<UUID, Integer> resolveThresholds(Collection<UUID> deviceIds) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(deviceIds));
        if (ids.isEmpty()) {
            return Map.of();
        }
        synchronized (cacheMonitor) {
            return resolveThresholdsLocked(ids);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void invalidateAfterCommit(
            String strategyType,
            Collection<UUID> deviceIds,
            InvalidationReason reason
    ) {
        if (strategyType == null || !STRATEGY_TYPE.equals(strategyType.strip().toUpperCase(Locale.ROOT))) {
            return;
        }
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(deviceIds));
        if (ids.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("有效策略缓存失效必须在活动事务中注册");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate(ids, reason);
            }
        });
    }

    private Map<UUID, Integer> resolveThresholdsLocked(List<UUID> ids) {
        long now = System.nanoTime();
        List<UUID> refreshIds = new ArrayList<>();
        int hitCount = 0;
        for (UUID deviceId : ids) {
            CachedPolicy cached = cache.get(deviceId);
            boolean retryDeferred = retryAfterNanos.getOrDefault(deviceId, 0L) > now;
            boolean expired = cached != null && now - cached.loadedAtNanos() >= cacheTtlNanos;
            boolean stale = staleDeviceIds.contains(deviceId);
            if (cached != null && (stale && retryDeferred || !stale && !expired)) {
                hitCount++;
            } else {
                refreshIds.add(deviceId);
            }
        }
        increment("mine.strategy.cache.access", "result", "hit", hitCount);
        increment("mine.strategy.cache.access", "result", "miss", refreshIds.size());
        if (!refreshIds.isEmpty()) {
            refresh(refreshIds, now);
        }
        Map<UUID, Integer> thresholds = new LinkedHashMap<>();
        ids.forEach(deviceId -> {
            CachedPolicy policy = cache.get(deviceId);
            if (policy != null && policy.thresholdSeconds() != null) {
                thresholds.put(deviceId, policy.thresholdSeconds());
            }
        });
        return Map.copyOf(thresholds);
    }

    private void refresh(List<UUID> deviceIds, long now) {
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            RefreshResult result = load(deviceIds);
            MineException firstFailure = null;
            RuntimeException firstFallbackFailure = null;
            int fallbackCount = 0;
            for (UUID deviceId : deviceIds) {
                RuntimeException failure = result.failures().get(deviceId);
                if (failure == null) {
                    CachedPolicy loaded = result.policies().getOrDefault(deviceId, CachedPolicy.absent());
                    cache.put(deviceId, loaded.loadedAt(now));
                    staleDeviceIds.remove(deviceId);
                    retryAfterNanos.remove(deviceId);
                    increment("mine.strategy.resolution", "result",
                            loaded.configured() ? "valid" : "absent", 1);
                    continue;
                }
                CachedPolicy previous = cache.get(deviceId);
                if (previous != null && previous.configured()) {
                    staleDeviceIds.add(deviceId);
                    retryAfterNanos.put(deviceId, now + RETRY_BACKOFF_NANOS);
                    fallbackCount++;
                    if (firstFallbackFailure == null) {
                        firstFallbackFailure = failure;
                    }
                } else {
                    increment("mine.strategy.resolution", "result", "error", 1);
                    if (firstFailure == null) {
                        firstFailure = invalid(deviceId, failure);
                    }
                }
            }
            increment("mine.strategy.resolution", "result", "stale_last_good", fallbackCount);
            if (fallbackCount > 0) {
                LOGGER.error("在线状态策略刷新失败，继续使用上一份已知良好值，受影响设备数={}",
                        fallbackCount, firstFallbackFailure);
            }
            trimCache();
            if (firstFailure != null) {
                throw firstFailure;
            }
        } catch (MineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            handleBatchFailure(deviceIds, now, exception);
        } finally {
            timer.stop(meterRegistry.timer(
                    "mine.strategy.resolution.duration",
                    "strategy_type", STRATEGY_TYPE
            ));
        }
    }

    private RefreshResult load(List<UUID> deviceIds) {
        Map<UUID, StrategyConfigurationMapper.DeviceContextRecord> contexts = new LinkedHashMap<>();
        for (int start = 0; start < deviceIds.size(); start += QUERY_BATCH_SIZE) {
            List<UUID> batch = deviceIds.subList(start, Math.min(start + QUERY_BATCH_SIZE, deviceIds.size()));
            mapper.findDeviceContexts(batch).stream()
                    .filter(context -> !"ARCHIVED".equals(context.status()))
                    .forEach(context -> contexts.put(context.id(), context));
        }

        Map<UUID, List<UUID>> byProduct = new LinkedHashMap<>();
        deviceIds.forEach(deviceId -> {
            StrategyConfigurationMapper.DeviceContextRecord context = contexts.get(deviceId);
            if (context != null) {
                byProduct.computeIfAbsent(context.productId(), ignored -> new ArrayList<>()).add(deviceId);
            }
        });

        Map<UUID, CachedPolicy> policies = new LinkedHashMap<>();
        Map<UUID, RuntimeException> failures = new LinkedHashMap<>();
        byProduct.forEach((productId, productDeviceIds) -> loadProduct(
                productId, productDeviceIds, policies, failures));
        return new RefreshResult(Map.copyOf(policies), Map.copyOf(failures));
    }

    private void loadProduct(
            UUID productId,
            List<UUID> deviceIds,
            Map<UUID, CachedPolicy> policies,
            Map<UUID, RuntimeException> failures
    ) {
        for (int start = 0; start < deviceIds.size(); start += QUERY_BATCH_SIZE) {
            List<UUID> batch = deviceIds.subList(start, Math.min(start + QUERY_BATCH_SIZE, deviceIds.size()));
            Map<UUID, List<StrategyConfigurationMapper.StrategyFragmentRecord>> fragments =
                    new LinkedHashMap<>();
            batch.forEach(deviceId -> fragments.put(deviceId, new ArrayList<>()));
            mapper.findCurrentFragmentsForDevices(productId, STRATEGY_TYPE, batch)
                    .forEach(fragment -> fragments.get(fragment.deviceId()).add(fragment));
            batch.forEach(deviceId -> {
                try {
                    policies.put(deviceId, resolve(fragments.get(deviceId)));
                } catch (RuntimeException exception) {
                    failures.put(deviceId, exception);
                }
            });
        }
    }

    private CachedPolicy resolve(List<StrategyConfigurationMapper.StrategyFragmentRecord> records) {
        if (records.isEmpty()) {
            return CachedPolicy.absent();
        }
        List<ScopedStrategyFragment> fragments = records.stream().map(record ->
                new ScopedStrategyFragment(
                        record.releaseId(),
                        StrategyScopeType.valueOf(record.scopeType()),
                        record.scopeId(),
                        record.groupPriority(),
                        definition.validate(readMap(record.valuesJson()))
                )).toList();
        EffectiveStrategyResolver.Resolution resolution = resolver.resolve(fragments);
        if (!resolution.valid()) {
            throw new IllegalStateException("数据库中的在线状态策略仍存在作用域冲突");
        }
        Object threshold = resolution.values().get(OnlineStatusStrategyDefinition.OFFLINE_THRESHOLD_SECONDS);
        if (!(threshold instanceof Integer value)) {
            throw new IllegalStateException("在线状态策略缺少离线判定阈值");
        }
        return new CachedPolicy(value, 0);
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的在线状态策略 JSON 无法解析", exception);
        }
    }

    private void handleBatchFailure(List<UUID> deviceIds, long now, RuntimeException failure) {
        UUID firstUnavailable = null;
        int fallbackCount = 0;
        for (UUID deviceId : deviceIds) {
            CachedPolicy previous = cache.get(deviceId);
            if (previous != null && previous.configured()) {
                staleDeviceIds.add(deviceId);
                retryAfterNanos.put(deviceId, now + RETRY_BACKOFF_NANOS);
                fallbackCount++;
            } else if (firstUnavailable == null) {
                firstUnavailable = deviceId;
            }
        }
        increment("mine.strategy.resolution", "result", "stale_last_good", fallbackCount);
        if (firstUnavailable != null) {
            increment("mine.strategy.resolution", "result", "error", 1);
            throw invalid(firstUnavailable, failure);
        }
        LOGGER.error("在线状态策略批量刷新失败，继续使用上一份已知良好值，受影响设备数={}",
                fallbackCount, failure);
    }

    private MineException invalid(UUID deviceId, RuntimeException cause) {
        MineException exception = new MineException(
                EFFECTIVE_STRATEGY_INVALID,
                Map.of("deviceId", deviceId, "strategyType", STRATEGY_TYPE)
        );
        exception.initCause(cause);
        return exception;
    }

    private void invalidate(List<UUID> deviceIds, InvalidationReason reason) {
        synchronized (cacheMonitor) {
            deviceIds.stream().filter(cache::containsKey).forEach(deviceId -> {
                staleDeviceIds.add(deviceId);
                retryAfterNanos.remove(deviceId);
            });
        }
        increment("mine.strategy.cache.invalidation", "reason",
                reason.name().toLowerCase(Locale.ROOT), deviceIds.size());
    }

    private void trimCache() {
        while (cache.size() > maximumCacheSize) {
            UUID eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
            staleDeviceIds.remove(eldest);
            retryAfterNanos.remove(eldest);
        }
    }

    private void increment(String name, String tagName, String tagValue, int amount) {
        if (amount > 0) {
            meterRegistry.counter(
                    name,
                    "strategy_type", STRATEGY_TYPE,
                    tagName, tagValue
            ).increment(amount);
        }
    }

    /**
     * 单设备缓存策略；阈值为空表示数据库当前没有任何在线状态策略发布。
     *
     * @param thresholdSeconds 已发布离线阈值
     * @param loadedAtNanos    最近成功加载的单调时钟时间
     */
    private record CachedPolicy(Integer thresholdSeconds, long loadedAtNanos) {
        private static CachedPolicy absent() {
            return new CachedPolicy(null, 0);
        }

        private boolean configured() {
            return thresholdSeconds != null;
        }

        private CachedPolicy loadedAt(long now) {
            return new CachedPolicy(thresholdSeconds, now);
        }
    }

    /**
     * 一次批量刷新中成功解析结果与逐设备失败原因。
     *
     * @param policies 成功解析的策略
     * @param failures 逐设备失败原因
     */
    private record RefreshResult(
            Map<UUID, CachedPolicy> policies,
            Map<UUID, RuntimeException> failures
    ) {
    }
}
