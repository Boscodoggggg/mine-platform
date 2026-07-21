package com.bosco.mine.gateway.configuration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

/**
 * 记录 Gateway 配置分发处理的低基数结果、阶段和耗时。
 *
 * <p>指标标签只允许本类枚举定义的有限值，禁止加入产品、发布或设备标识。</p>
 */
public final class ConfigurationDistributionMetrics {

    private static final String DISTRIBUTION_METRIC = "mine.gateway.configuration.distribution";
    private static final String DURATION_METRIC = "mine.gateway.configuration.distribution.duration";
    private static final String STAGE_METRIC = "mine.gateway.configuration.stage";

    private final MeterRegistry registry;

    /**
     * 创建配置分发指标记录器。
     *
     * @param registry Micrometer 指标注册表
     */
    public ConfigurationDistributionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "指标注册表不能为空");
    }

    /** @return 一次配置事件处理的耗时样本 */
    public ProcessingSample start() {
        return new ProcessingSample(Timer.start(registry));
    }

    /**
     * 记录配置事件的最终处理结果和耗时。
     *
     * @param sample 处理耗时样本
     * @param outcome 有限结果类型
     */
    public void complete(ProcessingSample sample, Outcome outcome) {
        Objects.requireNonNull(sample, "处理耗时样本不能为空");
        Objects.requireNonNull(outcome, "处理结果不能为空");
        Counter.builder(DISTRIBUTION_METRIC)
                .description("Gateway 配置发布事件处理结果")
                .tag("outcome", outcome.tag)
                .register(registry)
                .increment();
        sample.timerSample.stop(Timer.builder(DURATION_METRIC)
                .description("Gateway 配置发布事件处理耗时")
                .tag("outcome", outcome.tag)
                .publishPercentileHistogram()
                .register(registry));
    }

    /**
     * 记录固定处理阶段的成功或失败次数。
     *
     * @param stage 固定处理阶段
     * @param result 固定阶段结果
     */
    public void stage(Stage stage, StageResult result) {
        Counter.builder(STAGE_METRIC)
                .description("Gateway 配置发布各固定阶段结果")
                .tags("stage", stage.tag, "result", result.tag)
                .register(registry)
                .increment();
    }

    /** 一次配置事件的单调时钟耗时样本。 */
    public static final class ProcessingSample {
        private final Timer.Sample timerSample;

        private ProcessingSample(Timer.Sample timerSample) {
            this.timerSample = timerSample;
        }
    }

    /** 配置事件最终处理结果。 */
    public enum Outcome {
        /** 快照已加载、持久化、回报并确认。 */
        LOADED("loaded"),
        /** 可定位发布的永久快照错误已回报并死信。 */
        PERMANENT_FAILURE("permanent_failure"),
        /** 网络、磁盘或状态回报失败，消息将重投。 */
        TRANSIENT_FAILURE("transient_failure"),
        /** 无法安全定位发布的非法事件已死信。 */
        INVALID_MESSAGE("invalid_message");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    /** 配置事件固定处理阶段。 */
    public enum Stage {
        /** 解码轻量事件。 */
        DECODE("decode"),
        /** 拉取完整快照。 */
        FETCH("fetch"),
        /** 比对事件与快照定位信息。 */
        VALIDATE("validate"),
        /** 编译并原子激活协议。 */
        ACTIVATE("activate"),
        /** 持久化最后已知良好快照。 */
        PERSIST("persist"),
        /** 回报成功或失败节点状态。 */
        REPORT("report");

        private final String tag;

        Stage(String tag) {
            this.tag = tag;
        }
    }

    /** 固定处理阶段结果。 */
    public enum StageResult {
        /** 阶段成功。 */
        SUCCESS("success"),
        /** 阶段失败。 */
        FAILURE("failure");

        private final String tag;

        StageResult(String tag) {
            this.tag = tag;
        }
    }
}
