package com.bosco.mine.telemetry.spatial;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * 汇总空间事件队列、评估、业务事件、Outbox 和重放的低基数运行指标。
 *
 * <p>标签值只能来自本类有限枚举，禁止加入组织、矿区、区域标识、标签、车辆、人员、
 * 请求标识或异常文本。</p>
 */
public final class SpatialEventMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(SpatialEventMetrics.class);
    private static final Set<String> WARNED_METRICS = ConcurrentHashMap.newKeySet();

    private static final String QUEUE_EVENTS_METRIC =
            "mine.telemetry.spatial.queue.events";
    private static final String STAGE_EVENTS_METRIC =
            "mine.telemetry.spatial.stage.events";
    private static final String EVENTS_METRIC = "mine.telemetry.spatial.events";
    private static final String QUEUE_BACKLOG_METRIC =
            "mine.telemetry.spatial.queue.backlog";
    private static final String OUTBOX_DEAD_METRIC =
            "mine.telemetry.spatial.outbox.dead";
    private static final String REPLAY_OLDEST_ACTIVE_AGE_METRIC =
            "mine.telemetry.spatial.replay.oldest.active.age.seconds";
    private static final SpatialEventMetrics NOOP = new SpatialEventMetrics();

    private final MeterRegistry registry;

    private SpatialEventMetrics() {
        this.registry = null;
    }

    /**
     * 创建空间事件指标并从 Telemetry 数据库读取当前积压、死信和重放年龄。
     *
     * @param registry Micrometer 指标注册表
     * @param dataSource Telemetry 时序数据库
     */
    public SpatialEventMetrics(MeterRegistry registry, DataSource dataSource) {
        this(registry, queueBacklogSupplier(dataSource), outboxDeadSupplier(dataSource),
                replayOldestActiveAgeSupplier(dataSource));
    }

    /**
     * 创建可测试的空间事件指标。
     *
     * @param registry Micrometer 指标注册表
     * @param queueBacklog 空间评估队列积压读取器
     * @param outboxDead 空间 Outbox 死信读取器
     * @param replayOldestActiveAge 最老活动重放请求年龄读取器
     */
    SpatialEventMetrics(
            MeterRegistry registry,
            DoubleSupplier queueBacklog,
            DoubleSupplier outboxDead,
            DoubleSupplier replayOldestActiveAge
    ) {
        this.registry = Objects.requireNonNull(registry, "空间事件指标注册表不能为空");
        registerGauge(registry, QUEUE_BACKLOG_METRIC,
                "空间评估队列等待和失败重试记录数", queueBacklog);
        registerGauge(registry, OUTBOX_DEAD_METRIC,
                "空间事件可靠发布死信记录数", outboxDead);
        registerGauge(registry, REPLAY_OLDEST_ACTIVE_AGE_METRIC,
                "最老活动空间重放请求年龄秒数", replayOldestActiveAge);
    }

    /**
     * 记录一条位置事实进入空间评估队列的固定结果。
     *
     * @param result 固定处理结果
     * @param reason 固定处理原因
     */
    public void queue(Result result, Reason reason) {
        increment(QUEUE_EVENTS_METRIC, "空间评估队列处理结果",
                Objects.requireNonNull(result, "空间队列结果不能为空"),
                Objects.requireNonNull(reason, "空间队列原因不能为空"), null, null, null);
    }

    /**
     * 记录空间运行固定阶段的结果。
     *
     * @param stage 固定运行阶段
     * @param result 固定处理结果
     * @param reason 固定处理原因
     */
    public void stage(Stage stage, Result result, Reason reason) {
        increment(STAGE_EVENTS_METRIC, "空间事件运行固定阶段结果",
                Objects.requireNonNull(result, "空间阶段结果不能为空"),
                Objects.requireNonNull(reason, "空间阶段原因不能为空"),
                Objects.requireNonNull(stage, "空间运行阶段不能为空"), null, null);
    }

    /**
     * 记录已提交空间业务事件的固定类型结果。
     *
     * @param zoneType 固定区域类型
     * @param eventType 固定事件类型
     * @param result 固定处理结果
     */
    public void event(ZoneType zoneType, EventType eventType, Result result) {
        increment(EVENTS_METRIC, "已提交空间业务事件结果",
                Objects.requireNonNull(result, "空间事件结果不能为空"), null, null,
                Objects.requireNonNull(zoneType, "空间区域类型不能为空"),
                Objects.requireNonNull(eventType, "空间事件类型不能为空"));
    }

    /** @return 不注册指标的兼容实例 */
    static SpatialEventMetrics noop() {
        return NOOP;
    }

    private void increment(
            String metric,
            String description,
            Result result,
            Reason reason,
            Stage stage,
            ZoneType zoneType,
            EventType eventType
    ) {
        if (registry == null) {
            return;
        }
        try {
            Counter.Builder builder = Counter.builder(metric).description(description);
            if (stage != null) {
                builder.tag("stage", stage.tag);
            }
            if (zoneType != null) {
                builder.tag("zoneType", zoneType.tag);
            }
            if (eventType != null) {
                builder.tag("eventType", eventType.tag);
            }
            builder.tag("result", result.tag);
            if (reason != null) {
                builder.tag("reason", reason.tag);
            }
            builder.register(registry).increment();
        } catch (RuntimeException exception) {
            warnOnce(metric, exception);
        }
    }

    private static void registerGauge(
            MeterRegistry registry,
            String name,
            String description,
            DoubleSupplier supplier
    ) {
        try {
            Gauge.builder(name, Objects.requireNonNull(supplier, name + "读取器不能为空"),
                            DoubleSupplier::getAsDouble)
                    .description(description)
                    .register(registry);
        } catch (RuntimeException exception) {
            warnOnce(name, exception);
        }
    }

    private static void warnOnce(String metric, RuntimeException exception) {
        if (WARNED_METRICS.add(metric)) {
            LOG.warn("空间运行指标更新失败，业务处理不受影响: metric={}, errorType={}",
                    metric, exception.getClass().getSimpleName());
        }
    }

    private static DoubleSupplier queueBacklogSupplier(DataSource dataSource) {
        JdbcTemplate jdbc = jdbc(dataSource);
        return countSupplier(jdbc, """
                SELECT COUNT(*)
                FROM spatial_evaluation_queue
                WHERE status IN ('PENDING', 'FAILED')
                """);
    }

    private static DoubleSupplier outboxDeadSupplier(DataSource dataSource) {
        JdbcTemplate jdbc = jdbc(dataSource);
        return countSupplier(jdbc, """
                SELECT COUNT(*)
                FROM spatial_event_outbox
                WHERE status = 'DEAD'
                """);
    }

    private static DoubleSupplier replayOldestActiveAgeSupplier(DataSource dataSource) {
        JdbcTemplate jdbc = jdbc(dataSource);
        return () -> {
            try {
                Double value = jdbc.queryForObject("""
                        SELECT COALESCE(MAX(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - created_at)), 0)
                               ::double precision
                        FROM spatial_replay_request
                        WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')
                        """, Double.class);
                return value == null ? 0D : value;
            } catch (RuntimeException exception) {
                return Double.NaN;
            }
        };
    }

    private static DoubleSupplier countSupplier(JdbcTemplate jdbc, String sql) {
        return () -> {
            try {
                Long value = jdbc.queryForObject(sql, Long.class);
                return value == null ? 0D : value.doubleValue();
            } catch (RuntimeException exception) {
                return Double.NaN;
            }
        };
    }

    private static JdbcTemplate jdbc(DataSource dataSource) {
        return new JdbcTemplate(Objects.requireNonNull(
                dataSource, "空间事件指标数据源不能为空"));
    }

    /** 空间运行固定阶段。 */
    public enum Stage {
        /** 事件时间队列。 */
        QUEUE("queue"),
        /** 区域状态评估。 */
        EVALUATION("evaluation"),
        /** 可靠事件发布。 */
        OUTBOX("outbox"),
        /** 显式历史重放。 */
        REPLAY("replay");

        private final String tag;

        Stage(String tag) {
            this.tag = tag;
        }
    }

    /** 空间运行固定结果。 */
    public enum Result {
        /** 已成功完成。 */
        SUCCESS("success"),
        /** 已安全跳过。 */
        SKIPPED("skipped"),
        /** 暂时或永久失败。 */
        FAILURE("failure"),
        /** 已耗尽重试并进入死信。 */
        DEAD("dead");

        private final String tag;

        Result(String tag) {
            this.tag = tag;
        }
    }

    /** 空间运行固定原因。 */
    public enum Reason {
        /** 正常处理。 */
        NONE("none"),
        /** 超过事件时间水位。 */
        TOO_LATE("too_late"),
        /** 当前没有生效区域。 */
        NO_ACTIVE_ZONE("no_active_zone"),
        /** 位置事实已经入队。 */
        DUPLICATE("duplicate"),
        /** 区域状态恢复或推进失败。 */
        STATE_RECOVERY("state_recovery"),
        /** 数据库租约过期。 */
        LEASE_EXPIRED("lease_expired"),
        /** 可靠发布失败。 */
        PUBLISH_FAILED("publish_failed"),
        /** 已耗尽有界重试。 */
        RETRY_EXHAUSTED("retry_exhausted"),
        /** 运行时间超过安全阈值。 */
        TIMEOUT("timeout");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }
    }

    /** 空间区域固定类型。 */
    public enum ZoneType {
        /** 圆形途径点。 */
        WAYPOINT("waypoint"),
        /** 多边形电子围栏。 */
        GEOFENCE("geofence");

        private final String tag;

        ZoneType(String tag) {
            this.tag = tag;
        }
    }

    /** 空间业务事件固定类型。 */
    public enum EventType {
        /** 进入区域。 */
        ENTER("enter"),
        /** 离开区域。 */
        EXIT("exit"),
        /** 区域内达到停留阈值。 */
        DWELL("dwell");

        private final String tag;

        EventType(String tag) {
            this.tag = tag;
        }
    }
}
