package com.bosco.mine.server.modules.system.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 将 Prometheus 历史指标聚合为稳定的系统运行结论。
 */
@Service
public class OperationsObservabilityService {

    private static final List<RegisteredItem> REGISTERED_ITEMS = List.of(
            new RegisteredItem("mine-server", "SERVICE"),
            new RegisteredItem("mine-gateway", "SERVICE"),
            new RegisteredItem("mine-telemetry", "SERVICE"),
            new RegisteredItem("rabbitmq", "MESSAGE"),
            new RegisteredItem("postgres", "STORAGE"),
            new RegisteredItem("redis", "STORAGE"),
            new RegisteredItem("timescale", "STORAGE")
    );
    private static final Set<String> REGISTERED_KEYS = REGISTERED_ITEMS.stream()
            .map(RegisteredItem::key)
            .collect(Collectors.toUnmodifiableSet());

    private final OperationsObservabilityQuery query;
    private final Clock clock;

    /**
     * 创建生产环境可观测性聚合服务。
     *
     * @param query 历史指标查询端口
     */
    @Autowired
    public OperationsObservabilityService(OperationsObservabilityQuery query) {
        this(query, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的可观测性聚合服务。
     *
     * @param query 历史指标查询端口
     * @param clock UTC 时钟
     */
    OperationsObservabilityService(OperationsObservabilityQuery query, Clock clock) {
        this.query = query;
        this.clock = clock;
    }

    /**
     * 查询指定范围的运行总览。
     *
     * @param range 时间范围
     * @return 运行总览
     */
    public ObservabilityOverview overview(TimeRange range) {
        OperationsObservabilityQuery.QueryResult result = query.query(range, REGISTERED_KEYS);
        Map<String, OperationsObservabilityQuery.ItemHistory> histories = result.items().stream()
                .filter(item -> REGISTERED_KEYS.contains(item.key()))
                .collect(Collectors.toMap(
                        OperationsObservabilityQuery.ItemHistory::key,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<ObservabilityItem> items = new ArrayList<>();
        for (RegisteredItem registered : REGISTERED_ITEMS) {
            items.add(toItem(registered, result.available() ? histories.get(registered.key()) : null));
        }
        items.sort(Comparator.comparingInt((ObservabilityItem item) -> statusRank(item.status()))
                .thenComparingInt(item -> registeredIndex(item.key())));
        long healthy = count(items, "UP");
        long degraded = count(items, "DEGRADED");
        long down = count(items, "DOWN");
        long unknown = count(items, "UNKNOWN");
        return new ObservabilityOverview(
                result.available() ? "AVAILABLE" : "UNAVAILABLE",
                range.code(),
                result.generatedAt() == null ? clock.instant() : result.generatedAt(),
                overallStatus(items),
                healthy,
                degraded,
                down,
                unknown,
                List.copyOf(items)
        );
    }

    /**
     * 查询单个已注册检查项的状态与延迟序列。
     *
     * @param key   稳定检查项键
     * @param range 时间范围
     * @return 单项趋势
     */
    public ObservabilitySeries series(String key, TimeRange range) {
        RegisteredItem registered = REGISTERED_ITEMS.stream()
                .filter(item -> item.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知运行检查项"));
        OperationsObservabilityQuery.QueryResult result = query.query(range, Set.of(key));
        OperationsObservabilityQuery.ItemHistory history = result.items().stream()
                .filter(item -> key.equals(item.key()))
                .findFirst()
                .orElse(null);
        List<SeriesPoint> points = result.available() && history != null
                ? mergePoints(history)
                : List.of();
        return new ObservabilitySeries(
                key,
                registered.category(),
                result.available() ? "AVAILABLE" : "UNAVAILABLE",
                range.code(),
                result.generatedAt() == null ? clock.instant() : result.generatedAt(),
                points
        );
    }

    private ObservabilityItem toItem(
            RegisteredItem registered,
            OperationsObservabilityQuery.ItemHistory history
    ) {
        if (history == null || history.statusPoints().isEmpty()) {
            return new ObservabilityItem(
                    registered.key(), registered.category(), "UNKNOWN", null, null, null, null, 0
            );
        }
        List<OperationsObservabilityQuery.StatusPoint> statuses = history.statusPoints().stream()
                .sorted(Comparator.comparing(OperationsObservabilityQuery.StatusPoint::time))
                .toList();
        String currentStatus = statusOf(statuses.getLast().value());
        long healthySamples = statuses.stream().filter(point -> point.value() > 0).count();
        double availability = Math.round(healthySamples * 1000.0 / statuses.size()) / 10.0;
        List<Long> latencies = history.latencyPoints().stream()
                .map(OperationsObservabilityQuery.LatencyPoint::millis)
                .sorted()
                .toList();
        Long average = latencies.isEmpty()
                ? null
                : Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0));
        Long p95 = latencies.isEmpty()
                ? null
                : latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1));
        return new ObservabilityItem(
                registered.key(),
                registered.category(),
                currentStatus,
                availability,
                average,
                p95,
                lastChangedAt(statuses),
                statuses.size()
        );
    }

    private List<SeriesPoint> mergePoints(OperationsObservabilityQuery.ItemHistory history) {
        Map<Instant, Long> latencies = history.latencyPoints().stream()
                .collect(Collectors.toMap(
                        OperationsObservabilityQuery.LatencyPoint::time,
                        OperationsObservabilityQuery.LatencyPoint::millis,
                        (left, right) -> right
                ));
        return history.statusPoints().stream()
                .sorted(Comparator.comparing(OperationsObservabilityQuery.StatusPoint::time))
                .map(point -> new SeriesPoint(point.time(), statusOf(point.value()), latencies.get(point.time())))
                .toList();
    }

    private Instant lastChangedAt(List<OperationsObservabilityQuery.StatusPoint> statuses) {
        int current = statuses.getLast().value();
        Instant changedAt = statuses.getFirst().time();
        for (int index = statuses.size() - 1; index > 0; index--) {
            if (statuses.get(index - 1).value() != current) {
                changedAt = statuses.get(index).time();
                break;
            }
        }
        return changedAt;
    }

    private String overallStatus(List<ObservabilityItem> items) {
        long healthy = count(items, "UP");
        long down = count(items, "DOWN");
        long degraded = count(items, "DEGRADED");
        long unknown = count(items, "UNKNOWN");
        if (unknown == items.size()) {
            return "UNKNOWN";
        }
        if (healthy == items.size()) {
            return "UP";
        }
        if (down == items.size()) {
            return "DOWN";
        }
        return down > 0 || degraded > 0 || unknown > 0 ? "DEGRADED" : "UP";
    }

    private long count(List<ObservabilityItem> items, String status) {
        return items.stream().filter(item -> status.equals(item.status())).count();
    }

    private int registeredIndex(String key) {
        for (int index = 0; index < REGISTERED_ITEMS.size(); index++) {
            if (REGISTERED_ITEMS.get(index).key().equals(key)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private int statusRank(String status) {
        return switch (status) {
            case "DOWN" -> 0;
            case "DEGRADED" -> 1;
            case "UP" -> 2;
            default -> 3;
        };
    }

    private String statusOf(int value) {
        return value > 0 ? "UP" : "DOWN";
    }

    /**
     * 允许查询的历史时间范围。
     */
    public enum TimeRange {
        HOUR("1h", Duration.ofHours(1)),
        SIX_HOURS("6h", Duration.ofHours(6)),
        DAY("24h", Duration.ofDays(1)),
        WEEK("7d", Duration.ofDays(7)),
        FIFTEEN_DAYS("15d", Duration.ofDays(15));

        private final String code;
        private final Duration duration;

        TimeRange(String code, Duration duration) {
            this.code = code;
            this.duration = duration;
        }

        /**
         * 将接口范围代码转换为受控枚举。
         *
         * @param code 范围代码
         * @return 时间范围
         */
        public static TimeRange fromCode(String code) {
            String normalized = code == null ? "24h" : code.strip().toLowerCase(Locale.ROOT);
            for (TimeRange value : values()) {
                if (value.code.equals(normalized)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("不支持的运行监控时间范围");
        }

        /**
         * 返回接口范围代码。
         *
         * @return 范围代码
         */
        public String code() {
            return code;
        }

        /**
         * 返回时间跨度。
         *
         * @return 时间跨度
         */
        public Duration duration() {
            return duration;
        }
    }

    private record RegisteredItem(String key, String category) {
    }

    /**
     * 运行可观测性总览。
     *
     * @param dataStatus 数据源状态
     * @param range      时间范围
     * @param generatedAt 生成时间
     * @param status     总体状态
     * @param healthy    正常数量
     * @param degraded   部分异常数量
     * @param down       异常数量
     * @param unknown    未知数量
     * @param items      检查项列表
     */
    public record ObservabilityOverview(
            String dataStatus,
            String range,
            Instant generatedAt,
            String status,
            long healthy,
            long degraded,
            long down,
            long unknown,
            List<ObservabilityItem> items
    ) {
    }

    /**
     * 单个检查项聚合结论。
     *
     * @param key                 稳定检查项键
     * @param category            分类
     * @param status              当前状态
     * @param availabilityPercent 可用率百分比
     * @param averageLatencyMillis 平均真实延迟毫秒数
     * @param p95LatencyMillis    P95 真实延迟毫秒数
     * @param lastChangedAt       最近状态变化时间
     * @param sampleCount         有效样本数
     */
    public record ObservabilityItem(
            String key,
            String category,
            String status,
            Double availabilityPercent,
            Long averageLatencyMillis,
            Long p95LatencyMillis,
            Instant lastChangedAt,
            long sampleCount
    ) {
    }

    /**
     * 单个检查项趋势响应。
     *
     * @param key         稳定检查项键
     * @param category    分类
     * @param dataStatus  数据源状态
     * @param range       时间范围
     * @param generatedAt 生成时间
     * @param points      趋势点
     */
    public record ObservabilitySeries(
            String key,
            String category,
            String dataStatus,
            String range,
            Instant generatedAt,
            List<SeriesPoint> points
    ) {
    }

    /**
     * 状态与真实延迟趋势点。
     *
     * @param time          采样时间
     * @param status        状态
     * @param latencyMillis 真实延迟毫秒数
     */
    public record SeriesPoint(Instant time, String status, Long latencyMillis) {
    }
}
