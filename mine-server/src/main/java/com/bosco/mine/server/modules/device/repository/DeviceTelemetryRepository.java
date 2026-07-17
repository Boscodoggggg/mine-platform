package com.bosco.mine.server.modules.device.repository;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备数值遥测的 Redis 最新值和 TimescaleDB 历史查询端口。
 */
public interface DeviceTelemetryRepository {

    /**
     * 从 Redis 查询单个属性最新值。
     *
     * @param deviceId 设备标识
     * @param propertyId 属性标识
     * @return 缓存中的最新值
     */
    Optional<TelemetryValue> findCachedLatest(UUID deviceId, String propertyId);

    /**
     * 从 TimescaleDB 查询单个属性最近历史值。
     *
     * @param deviceId 设备标识
     * @param propertyId 属性标识
     * @return 最近历史值
     */
    Optional<TelemetryValue> findLatestHistory(UUID deviceId, String propertyId);

    /**
     * 一次查询多个属性的最近历史值，避免冷缓存请求产生逐属性 SQL。
     *
     * @param deviceId 设备标识
     * @param propertyIds 属性标识集合
     * @return 以属性标识为键的最近历史值
     */
    Map<String, TelemetryValue> findLatestHistory(UUID deviceId, List<String> propertyIds);

    /**
     * 从持久化读模型恢复设备顺序状态和已退役启动周期。
     *
     * @param deviceId 设备标识
     */
    void restoreLatestState(UUID deviceId);

    /**
     * 仅当 Redis 属性不存在时写入历史回源结果，避免覆盖并发到达的新遥测。
     *
     * @param deviceId 设备标识
     * @param value 待写入最新值
     */
    boolean cacheLatestIfAbsent(UUID deviceId, TelemetryValue value);

    /**
     * 读取当前已提交遥测历史的全局摄取水位。
     *
     * @return 当前摄取水位，无历史数据时返回零
     */
    long currentIngestionWatermark();

    /**
     * 在同一数据库快照中读取历史分页需要固定的原始摄取和聚合版本双水位。
     *
     * @return 当前可见的历史双水位
     */
    HistoryWatermarks currentHistoryWatermarks();

    /** 判断设备属性是否存在指定桶宽的当前启用聚合规则。 */
    boolean hasAggregationRule(UUID deviceId, String propertyId, int bucketSeconds);

    /** 查询最近的聚合执行事实。 */
    List<AggregationExecution> findRecentAggregationExecutions(int limit);

    /**
     * 查询原始或聚合历史分页。
     *
     * @param query 历史查询条件
     * @return 历史分页数据
     */
    HistoryPage findHistory(HistoryQuery query);

    /**
     * 历史聚合方式。
     */
    enum Aggregation {
        /** 原始点。 */
        RAW,
        /** 平均值。 */
        AVG,
        /** 最小值。 */
        MIN,
        /** 最大值。 */
        MAX
    }

    /** 历史点使用的持久数据来源。 */
    enum HistorySource {
        /** 仍由原始遥测直接读取或临时计算。 */
        RAW,
        /** 由版本化长期聚合事实读取。 */
        ROLLUP
    }

    /**
     * 历史分页快照的双水位。
     *
     * @param ingestionWatermark 原始遥测最大摄取水位
     * @param rollupWatermark 已收敛聚合执行中的最大聚合版本水位
     */
    record HistoryWatermarks(long ingestionWatermark, long rollupWatermark) {
        /** 创建并校验非负双水位。 */
        public HistoryWatermarks {
            if (ingestionWatermark < 0 || rollupWatermark < 0) {
                throw new IllegalArgumentException("历史查询双水位不能为负数");
            }
        }
    }

    /**
     * Telemetry 已持久化的单轮聚合执行事实。
     *
     * @param executionId 执行标识
     * @param instanceId 运行实例标识
     * @param status 终态或运行中状态
     * @param policyCount 策略数量
     * @param ruleCount 规则数量
     * @param sourceWatermark 原始摄取水位
     * @param bucketCount 已提交桶数
     * @param rollupWatermark 最大聚合版本水位
     * @param lateRowsIgnored 超窗口忽略行数
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     * @param lastError 脱敏错误摘要
     */
    record AggregationExecution(
            UUID executionId,
            String instanceId,
            String status,
            int policyCount,
            int ruleCount,
            long sourceWatermark,
            int bucketCount,
            Long rollupWatermark,
            long lateRowsIgnored,
            Instant startedAt,
            Instant completedAt,
            String lastError
    ) {
    }

    /**
     * 最新遥测值。
     *
     * @param propertyId 属性标识
     * @param value 数值
     * @param occurredAt 设备发生时间
     * @param effectiveAt 平台时间质量策略判定后的有效排序时间
     * @param receivedAt 平台接收时间
     * @param quality 数据质量
     * @param eventId 事件标识
     * @param bootId 设备启动周期标识
     * @param sequence 启动周期内序号
     */
    record TelemetryValue(
            String propertyId,
            double value,
            Instant occurredAt,
            Instant effectiveAt,
            Instant receivedAt,
            Quality quality,
            UUID eventId,
            String bootId,
            Long sequence
    ) {

        /**
         * 创建并校验最新遥测值。
         */
        public TelemetryValue {
            propertyId = Objects.requireNonNull(propertyId, "属性标识不能为空");
            occurredAt = Objects.requireNonNull(occurredAt, "发生时间不能为空");
            effectiveAt = Objects.requireNonNull(effectiveAt, "有效排序时间不能为空");
            receivedAt = Objects.requireNonNull(receivedAt, "接收时间不能为空");
            quality = Objects.requireNonNull(quality, "数据质量不能为空");
            eventId = Objects.requireNonNull(eventId, "事件标识不能为空");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("遥测值必须是有限数值");
            }
        }
    }

    /**
     * 历史查询条件。
     *
     * @param deviceId 设备标识
     * @param propertyId 属性标识
     * @param from 开始时间，包含
     * @param to 结束时间，不包含
     * @param aggregation 聚合方式
     * @param bucket 聚合时间桶，原始查询为空
     * @param watermark 分页数据集的全局摄取水位上限
     * @param rollupWatermark 分页数据集的全局聚合版本水位上限
     * @param offset 分页偏移
     * @param limit 最大返回数量
     */
    record HistoryQuery(
            UUID deviceId,
            String propertyId,
            Instant from,
            Instant to,
            Aggregation aggregation,
            Duration bucket,
            long watermark,
            long rollupWatermark,
            int offset,
            int limit
    ) {
    }

    /**
     * 原始遥测点或聚合时间桶。
     *
     * @param occurredAt 原始发生时间或桶起始时间
     * @param receivedAt 平台接收时间或桶内最晚接收时间
     * @param value 原始值或聚合值
     * @param quality 数据质量或桶内最差质量
     * @param eventId 原始事件标识，聚合结果为空
     * @param sampleCount 样本数量
     * @param source 原始数据或长期聚合事实来源
     */
    record HistoryPoint(
            Instant occurredAt,
            Instant receivedAt,
            double value,
            Quality quality,
            UUID eventId,
            long sampleCount,
            HistorySource source
    ) {
    }

    /**
     * 历史查询分页结果。
     *
     * @param items 当前页数据
     * @param total 原始点总数或聚合桶总数
     */
    record HistoryPage(List<HistoryPoint> items, long total) {

        /**
         * 创建不可变历史分页结果。
         */
        public HistoryPage {
            items = List.copyOf(Objects.requireNonNull(items, "历史数据不能为空"));
            if (total < 0) {
                throw new IllegalArgumentException("历史总数不能为负数");
            }
        }
    }
}
