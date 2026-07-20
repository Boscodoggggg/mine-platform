package com.bosco.mine.server.modules.mine.operation.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * 汇总生产运营持久队列、运输循环、候选、执行事实和关账遗留指标。
 *
 * <p>指标标签只能来自本类有限枚举。组织、矿区、作业单、车辆、司机、空间事件、
 * 异常文本等业务身份或高基数内容不得进入指标。</p>
 */
@Component
public final class OperationMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationMetrics.class);
    private static final Set<String> WARNED_METRICS = ConcurrentHashMap.newKeySet();
    private static final String QUEUE_EVENTS_METRIC =
            "mine.server.operation.queue.events";
    private static final String STAGE_EVENTS_METRIC =
            "mine.server.operation.stage.events";
    private static final String CANDIDATES_METRIC =
            "mine.server.operation.candidates";
    private static final String RECORDS_METRIC =
            "mine.server.operation.records";
    private static final String QUEUE_BACKLOG_METRIC =
            "mine.server.operation.queue.backlog";
    private static final String OUTBOX_DEAD_METRIC =
            "mine.server.operation.outbox.dead";
    private static final String SHIFT_CLOSE_LEFTOVERS_METRIC =
            "mine.server.operation.shift.close.leftovers";
    private static final OperationMetrics NOOP = new OperationMetrics();

    private final MeterRegistry registry;

    private OperationMetrics() {
        this.registry = null;
    }

    /**
     * 创建运营指标，并从管理数据库实时读取积压、死信和到期班次遗留。
     *
     * @param registry Micrometer 指标注册表
     * @param dataSource 管理数据库数据源
     */
    @Autowired
    public OperationMetrics(MeterRegistry registry, DataSource dataSource) {
        this(registry, queueBacklogSupplier(dataSource), outboxDeadSupplier(dataSource),
                shiftCloseLeftoversSupplier(dataSource));
    }

    /**
     * 创建可使用固定读取器验证的运营指标。
     *
     * @param registry Micrometer 指标注册表
     * @param queueBacklog 待处理或待重试运营空间事件数量读取器
     * @param outboxDead 运营实时事件死信数量读取器
     * @param shiftCloseLeftovers 到期开放班次关账遗留数量读取器
     */
    OperationMetrics(
            MeterRegistry registry,
            DoubleSupplier queueBacklog,
            DoubleSupplier outboxDead,
            DoubleSupplier shiftCloseLeftovers
    ) {
        this.registry = Objects.requireNonNull(registry, "运营指标注册表不能为空");
        registerGauge(registry, QUEUE_BACKLOG_METRIC,
                "运营空间事件持久队列待处理和待重试记录数", queueBacklog);
        registerGauge(registry, OUTBOX_DEAD_METRIC,
                "生产运营实时事件 Outbox 死信记录数", outboxDead);
        registerGauge(registry, SHIFT_CLOSE_LEFTOVERS_METRIC,
                "超过计划结束时间且仍有未终结作业或运输循环的开放班次数", shiftCloseLeftovers);
    }

    /**
     * 记录持久运营空间事件的固定处理结果。
     *
     * @param result 固定处理结果
     * @param reason 固定处理原因
     */
    public void queue(Result result, Reason reason) {
        Result safeResult = Objects.requireNonNull(result, "运营队列结果不能为空");
        Reason safeReason = Objects.requireNonNull(reason, "运营队列原因不能为空");
        incrementAfterCommit(QUEUE_EVENTS_METRIC, "运营空间事件持久处理结果",
                builder -> builder.tag("result", safeResult.tag)
                        .tag("reason", safeReason.tag));
    }

    /**
     * 记录运营运行阶段的固定结果。
     *
     * @param stage 固定运行阶段
     * @param result 固定处理结果
     * @param reason 固定处理原因
     */
    public void stage(Stage stage, Result result, Reason reason) {
        Stage safeStage = Objects.requireNonNull(stage, "运营运行阶段不能为空");
        Result safeResult = Objects.requireNonNull(result, "运营阶段结果不能为空");
        Reason safeReason = Objects.requireNonNull(reason, "运营阶段原因不能为空");
        incrementAfterCommit(STAGE_EVENTS_METRIC, "生产运营固定阶段处理结果",
                builder -> builder.tag("stage", safeStage.tag)
                        .tag("result", safeResult.tag)
                        .tag("reason", safeReason.tag));
    }

    /**
     * 记录首次持久创建的固定类型运营候选。
     *
     * @param candidateType 固定候选类型
     * @param reason 固定候选原因
     */
    public void candidate(CandidateType candidateType, Reason reason) {
        CandidateType safeType = Objects.requireNonNull(
                candidateType, "运营候选类型不能为空");
        Reason safeReason = Objects.requireNonNull(reason, "运营候选原因不能为空");
        incrementAfterCommit(CANDIDATES_METRIC, "首次创建的生产运营待确认候选",
                builder -> builder.tag("candidateType", safeType.tag)
                        .tag("reason", safeReason.tag));
    }

    /**
     * 将持久候选的固定代码安全映射到有限指标标签，未知代码只进入固定 unknown 桶。
     *
     * @param candidateType 固定候选类型代码
     * @param reasonCode 固定原因代码
     */
    void candidate(String candidateType, String reasonCode) {
        candidate(CandidateType.fromCode(candidateType), Reason.fromCode(reasonCode));
    }

    /**
     * 记录形成不可变执行事实的固定作业类型结果。
     *
     * @param workType 固定作业类型
     * @param result 固定处理结果
     */
    public void record(WorkType workType, Result result) {
        WorkType safeType = Objects.requireNonNull(workType, "运营作业类型不能为空");
        Result safeResult = Objects.requireNonNull(result, "运营记录结果不能为空");
        incrementAfterCommit(RECORDS_METRIC, "生产运营不可变执行事实形成结果",
                builder -> builder.tag("workType", safeType.tag)
                        .tag("result", safeResult.tag));
    }

    /**
     * 返回不注册、不更新任何指标的兼容实例。
     *
     * @return 空操作运营指标
     */
    static OperationMetrics noop() {
        return NOOP;
    }

    private void incrementAfterCommit(
            String metric,
            String description,
            CounterCustomizer customizer
    ) {
        if (registry == null) {
            return;
        }
        Runnable action = () -> {
            try {
                Counter.Builder builder = Counter.builder(metric).description(description);
                customizer.customize(builder).register(registry).increment();
            } catch (RuntimeException exception) {
                warnOnce(metric, exception);
            }
        };
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
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

    private static DoubleSupplier queueBacklogSupplier(DataSource dataSource) {
        return countSupplier(jdbc(dataSource), """
                SELECT COUNT(*)
                FROM mine_operation_spatial_event_queue
                WHERE status IN ('PENDING', 'FAILED')
                """);
    }

    private static DoubleSupplier outboxDeadSupplier(DataSource dataSource) {
        return countSupplier(jdbc(dataSource), """
                SELECT COUNT(*)
                FROM mine_operation_outbox
                WHERE status = 'DEAD'
                """);
    }

    private static DoubleSupplier shiftCloseLeftoversSupplier(DataSource dataSource) {
        return countSupplier(jdbc(dataSource), """
                SELECT COUNT(*)
                FROM mine_shift_instance shift_instance
                WHERE shift_instance.status = 'OPEN'
                  AND shift_instance.planned_end_at <= CURRENT_TIMESTAMP
                  AND (
                    EXISTS (
                        SELECT 1
                        FROM mine_work_order work_order
                        WHERE work_order.shift_instance_id = shift_instance.id
                          AND work_order.organization_id = shift_instance.organization_id
                          AND work_order.site_id = shift_instance.site_id
                          AND work_order.status NOT IN ('COMPLETED', 'CANCELLED', 'ABORTED')
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM mine_operation_runtime_state runtime_state
                        JOIN mine_work_order work_order
                          ON work_order.id = runtime_state.work_order_id
                         AND work_order.organization_id = runtime_state.organization_id
                         AND work_order.site_id = runtime_state.site_id
                        WHERE work_order.shift_instance_id = shift_instance.id
                    )
                  )
                """);
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
                dataSource, "生产运营指标数据源不能为空"));
    }

    private static void warnOnce(String metric, RuntimeException exception) {
        if (WARNED_METRICS.add(metric)) {
            LOGGER.warn("生产运营指标更新失败，业务处理不受影响: metric={}, errorType={}",
                    metric, exception.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface CounterCustomizer {
        Counter.Builder customize(Counter.Builder builder);
    }

    /** 生产运营固定运行阶段。 */
    public enum Stage {
        /** 持久空间事件队列。 */
        QUEUE("queue"),
        /** 运输循环自动化。 */
        AUTOMATION("automation"),
        /** 运营实时事件可靠发布。 */
        OUTBOX("outbox"),
        /** 班次关账。 */
        SHIFT_CLOSE("shift_close");

        private final String tag;

        Stage(String tag) {
            this.tag = tag;
        }
    }

    /** 生产运营固定处理结果。 */
    public enum Result {
        /** 已成功完成。 */
        SUCCESS("success"),
        /** 已安全跳过。 */
        SKIPPED("skipped"),
        /** 暂时处理失败。 */
        FAILURE("failure"),
        /** 已耗尽重试并进入死信。 */
        DEAD("dead");

        private final String tag;

        Result(String tag) {
            this.tag = tag;
        }
    }

    /** 生产运营固定处理原因。 */
    public enum Reason {
        /** 正常处理。 */
        NONE("none"),
        /** 事件已成为当前运行态证据。 */
        DUPLICATE("duplicate"),
        /** 事件落后于最终处理前沿。 */
        TOO_LATE("too_late"),
        /** 运输循环状态恢复或推进失败。 */
        STATE_ADVANCE("state_advance"),
        /** 当前处理租约已经丢失。 */
        LEASE_LOST("lease_lost"),
        /** 可靠发布失败。 */
        PUBLISH_FAILED("publish_failed"),
        /** 已耗尽有界重试次数。 */
        RETRY_EXHAUSTED("retry_exhausted"),
        /** 标签在事件发生时没有车辆绑定。 */
        NO_TAG_BINDING("no_tag_binding"),
        /** 标签在事件发生时存在多个车辆绑定。 */
        AMBIGUOUS_TAG_BINDING("ambiguous_tag_binding"),
        /** 事件车辆与标签绑定车辆不一致。 */
        EVENT_VEHICLE_TAG_MISMATCH("event_vehicle_tag_mismatch"),
        /** 没有唯一活动作业分配。 */
        NO_UNIQUE_ACTIVE_ASSIGNMENT("no_unique_active_assignment"),
        /** 班次在事件发生时未开放。 */
        SHIFT_NOT_OPEN_AT_EVENT_TIME("shift_not_open_at_event_time"),
        /** 作业单当前不处于活动状态。 */
        WORK_ORDER_NOT_ACTIVE("work_order_not_active"),
        /** 作业单在事件发生时处于暂停状态。 */
        WORK_ORDER_PAUSED_AT_EVENT_TIME("work_order_paused_at_event_time"),
        /** 作业单在事件发生时未运行。 */
        WORK_ORDER_NOT_RUNNING_AT_EVENT_TIME("work_order_not_running_at_event_time"),
        /** 当前运输步骤超过允许持续时间。 */
        STEP_TIMEOUT("step_timeout"),
        /** 空间事件车辆身份不匹配。 */
        VEHICLE_MISMATCH("vehicle_mismatch"),
        /** 区域版本与作业发布快照不匹配。 */
        ZONE_VERSION_MISMATCH("zone_version_mismatch"),
        /** 空间事件业务顺序无效。 */
        OUT_OF_ORDER("out_of_order"),
        /** 当前运输步骤不接受该空间事件。 */
        UNEXPECTED_EVENT("unexpected_event"),
        /** 未识别代码进入固定桶，避免生成动态标签。 */
        UNKNOWN("unknown");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }

        private static Reason fromCode(String value) {
            try {
                return value == null ? UNKNOWN
                        : valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return UNKNOWN;
            }
        }
    }

    /** 生产运营固定候选类型。 */
    public enum CandidateType {
        /** 车辆或标签身份无法唯一确认。 */
        VEHICLE_MISMATCH("vehicle_mismatch"),
        /** 没有唯一可用作业匹配。 */
        AMBIGUOUS_MATCH("ambiguous_match"),
        /** 空间事件落入已关账班次。 */
        CLOSED_SHIFT("closed_shift"),
        /** 空间事件落入暂停作业。 */
        PAUSED_WORK_ORDER("paused_work_order"),
        /** 空间事件超过最终处理前沿。 */
        OUT_OF_ORDER("out_of_order"),
        /** 空间事件不符合当前运输步骤。 */
        UNEXPECTED_EVENT("unexpected_event"),
        /** 未识别代码进入固定桶，避免生成动态标签。 */
        UNKNOWN("unknown");

        private final String tag;

        CandidateType(String tag) {
            this.tag = tag;
        }

        private static CandidateType fromCode(String value) {
            try {
                return value == null ? UNKNOWN
                        : valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return UNKNOWN;
            }
        }
    }

    /** 生产运营固定作业类型。 */
    public enum WorkType {
        /** 矿卡运输作业。 */
        HAULAGE("haulage");

        private final String tag;

        WorkType(String tag) {
            this.tag = tag;
        }
    }
}
