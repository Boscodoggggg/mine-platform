package com.bosco.mine.telemetry.location;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * 汇总定位事实处理、坐标转换、Redis 当前位置和隔离区低基数指标。
 */
public final class LocationPipelineMetrics {

    private static final String INGEST_METRIC = "mine.telemetry.location.ingest";
    private static final String TRANSFORM_METRIC = "mine.telemetry.location.transform";
    private static final String REDIS_METRIC = "mine.telemetry.location.redis.latest";
    private static final String QUARANTINE_SIZE_METRIC =
            "mine.telemetry.location.quarantine.size";
    private static final LocationPipelineMetrics NOOP = new LocationPipelineMetrics();

    private final MeterRegistry registry;

    private LocationPipelineMetrics() {
        this.registry = null;
    }

    /**
     * 创建定位流水线指标，并以只读查询暴露当前隔离区规模。
     *
     * @param registry Micrometer 指标注册表
     * @param dataSource Telemetry 时序数据库
     */
    public LocationPipelineMetrics(MeterRegistry registry, DataSource dataSource) {
        this(registry, quarantineSizeSupplier(dataSource));
    }

    /**
     * 创建可测试的定位流水线指标。
     *
     * @param registry Micrometer 指标注册表
     * @param quarantineSize 隔离区大小读取器
     */
    LocationPipelineMetrics(MeterRegistry registry, DoubleSupplier quarantineSize) {
        this.registry = Objects.requireNonNull(registry, "定位流水线指标注册表不能为空");
        Objects.requireNonNull(quarantineSize, "定位隔离区大小读取器不能为空");
        Gauge.builder(QUARANTINE_SIZE_METRIC, quarantineSize,
                        DoubleSupplier::getAsDouble)
                .description("Telemetry 定位隔离区当前记录数量")
                .register(registry);
    }

    /**
     * 记录定位观测的最终处理结果。
     *
     * @param result 固定处理结果
     * @param reason 固定处理原因
     */
    public void ingest(IngestResult result, IngestReason reason) {
        Objects.requireNonNull(result, "定位处理结果不能为空");
        Objects.requireNonNull(reason, "定位处理原因不能为空");
        if (registry == null) {
            return;
        }
        Counter.builder(INGEST_METRIC)
                .description("Telemetry 定位观测最终处理结果")
                .tags("result", result.tag, "reason", reason.tag)
                .register(registry)
                .increment();
    }

    /**
     * 记录固定坐标转换算法结果。
     *
     * @param algorithm 固定转换算法
     * @param result 成功或失败
     */
    public void transform(TransformAlgorithm algorithm, TransformResult result) {
        Objects.requireNonNull(algorithm, "坐标转换算法不能为空");
        Objects.requireNonNull(result, "坐标转换结果不能为空");
        if (registry == null) {
            return;
        }
        Counter.builder(TRANSFORM_METRIC)
                .description("Telemetry 定位坐标转换结果")
                .tags("algorithm", algorithm.tag, "result", result.tag)
                .register(registry)
                .increment();
    }

    /**
     * 记录 Redis 当前位置刷新结果。
     *
     * @param result 更新、顺序忽略或失败
     */
    public void redisLatest(RedisResult result) {
        Objects.requireNonNull(result, "定位 Redis 结果不能为空");
        if (registry == null) {
            return;
        }
        Counter.builder(REDIS_METRIC)
                .description("Telemetry Redis 当前位置刷新结果")
                .tag("result", result.tag)
                .register(registry)
                .increment();
    }

    /** @return 不注册指标的兼容实例 */
    static LocationPipelineMetrics noop() {
        return NOOP;
    }

    private static DoubleSupplier quarantineSizeSupplier(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(
                dataSource, "定位指标数据源不能为空"));
        return () -> {
            try {
                Long size = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM location_quarantine", Long.class);
                return size == null ? 0D : size.doubleValue();
            } catch (RuntimeException exception) {
                return Double.NaN;
            }
        };
    }

    /** Telemetry 定位处理固定结果。 */
    public enum IngestResult {
        /** 新事实已存储。 */
        STORED("stored"),
        /** 已处理内容的幂等重放。 */
        DUPLICATE("duplicate"),
        /** 永久业务错误已隔离。 */
        QUARANTINED("quarantined"),
        /** 无法形成安全隔离候选的非法消息。 */
        INVALID_MESSAGE("invalid_message"),
        /** 永久幂等或契约冲突。 */
        PERMANENT_FAILURE("permanent_failure"),
        /** 暂时存储故障并重新投递。 */
        TRANSIENT_FAILURE("transient_failure");

        private final String tag;

        IngestResult(String tag) {
            this.tag = tag;
        }
    }

    /** Telemetry 定位处理固定原因。 */
    public enum IngestReason {
        /** 正常处理。 */
        NONE("none"),
        /** 无法识别的既有隔离原因。 */
        UNKNOWN("unknown"),
        /** 未登记标签。 */
        UNKNOWN_TAG("unknown_tag"),
        /** 已归档标签。 */
        ARCHIVED_TAG("archived_tag"),
        /** 来源设备未授权。 */
        SOURCE_DEVICE_UNAUTHORIZED("source_device_unauthorized"),
        /** 坐标不是有限数字。 */
        NON_FINITE_COORDINATE("non_finite_coordinate"),
        /** 坐标超出边界。 */
        OUT_OF_BOUNDS("out_of_bounds"),
        /** 缺少转换。 */
        TRANSFORM_MISSING("transform_missing"),
        /** 转换版本冲突。 */
        TRANSFORM_VERSION_CONFLICT("transform_version_conflict"),
        /** 定位快照不可用。 */
        SNAPSHOT_UNAVAILABLE("snapshot_unavailable"),
        /** 定位快照非法。 */
        SNAPSHOT_INVALID("snapshot_invalid"),
        /** 定位观测格式非法。 */
        INVALID_OBSERVATION("invalid_observation"),
        /** 消息信封或正文无法安全解析。 */
        INVALID_MESSAGE("invalid_message"),
        /** 幂等身份对应了不同内容。 */
        IDEMPOTENCY_CONFLICT("idempotency_conflict"),
        /** 数据库或依赖存储暂时失败。 */
        STORAGE_FAILURE("storage_failure");

        private final String tag;

        IngestReason(String tag) {
            this.tag = tag;
        }
    }

    /** 固定坐标转换算法。 */
    public enum TransformAlgorithm {
        /** 恒等转换。 */
        IDENTITY("identity"),
        /** 三维仿射转换。 */
        AFFINE_3D("affine_3d"),
        /** WGS84 到局部东北天。 */
        WGS84_ENU("wgs84_enu");

        private final String tag;

        TransformAlgorithm(String tag) {
            this.tag = tag;
        }
    }

    /** 坐标转换固定结果。 */
    public enum TransformResult {
        /** 转换成功。 */
        SUCCESS("success"),
        /** 转换失败。 */
        FAILURE("failure");

        private final String tag;

        TransformResult(String tag) {
            this.tag = tag;
        }
    }

    /** Redis 当前位置固定结果。 */
    public enum RedisResult {
        /** 已刷新为更新事实。 */
        UPDATED("updated"),
        /** 因顺序或质量保护忽略。 */
        IGNORED("ignored"),
        /** Redis 操作失败。 */
        FAILURE("failure");

        private final String tag;

        RedisResult(String tag) {
            this.tag = tag;
        }
    }
}
