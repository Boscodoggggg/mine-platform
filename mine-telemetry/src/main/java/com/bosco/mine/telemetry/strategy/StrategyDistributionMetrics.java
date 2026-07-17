package com.bosco.mine.telemetry.strategy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

/**
 * 记录 Telemetry 策略运行快照处理的低基数结果、阶段和耗时。
 *
 * <p>标签值只允许使用本类枚举定义的有限集合，禁止加入产品、设备、快照或异常文本。</p>
 */
public final class StrategyDistributionMetrics {

    private static final String DISTRIBUTION_METRIC =
            "mine.telemetry.strategy.distribution";
    private static final String DURATION_METRIC =
            "mine.telemetry.strategy.distribution.duration";
    private static final String STAGE_METRIC = "mine.telemetry.strategy.stage";

    private final MeterRegistry registry;

    /**
     * 创建策略分发指标记录器。
     *
     * @param registry Micrometer 指标注册表
     */
    public StrategyDistributionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "策略分发指标注册表不能为空");
    }

    /** @return 一次策略事件处理的单调时钟耗时样本 */
    public ProcessingSample start() {
        return new ProcessingSample(Timer.start(registry));
    }

    /**
     * 记录策略事件最终结果和处理耗时。
     *
     * @param sample 处理耗时样本
     * @param outcome 有限结果类型
     */
    public void complete(ProcessingSample sample, Outcome outcome) {
        Objects.requireNonNull(sample, "策略处理耗时样本不能为空");
        Objects.requireNonNull(outcome, "策略处理结果不能为空");
        Counter.builder(DISTRIBUTION_METRIC)
                .description("Telemetry 策略运行事件处理结果")
                .tag("outcome", outcome.tag)
                .register(registry)
                .increment();
        sample.timerSample.stop(Timer.builder(DURATION_METRIC)
                .description("Telemetry 策略运行事件处理耗时")
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
        Objects.requireNonNull(stage, "策略处理阶段不能为空");
        Objects.requireNonNull(result, "策略阶段结果不能为空");
        Counter.builder(STAGE_METRIC)
                .description("Telemetry 策略运行事件各固定阶段结果")
                .tags("stage", stage.tag, "result", result.tag)
                .register(registry)
                .increment();
    }

    /** 一次策略事件的单调时钟耗时样本。 */
    public static final class ProcessingSample {
        private final Timer.Sample timerSample;

        private ProcessingSample(Timer.Sample timerSample) {
            this.timerSample = timerSample;
        }
    }

    /** 策略事件最终处理结果。 */
    public enum Outcome {
        /** 快照已校验并原子激活。 */
        LOADED("loaded"),
        /** 已定位事件的永久快照错误进入死信。 */
        PERMANENT_FAILURE("permanent_failure"),
        /** 网络或数据库暂时失败，消息将重投。 */
        TRANSIENT_FAILURE("transient_failure"),
        /** 无法安全定位快照的非法事件进入死信。 */
        INVALID_MESSAGE("invalid_message");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    /** 策略事件固定处理阶段。 */
    public enum Stage {
        /** 解码和校验轻量事件信封。 */
        DECODE("decode"),
        /** 从控制面分页拉取不可变快照。 */
        FETCH("fetch"),
        /** 校验头、条目、排序和完整哈希。 */
        VALIDATE("validate"),
        /** 将校验后的分页条目持久化到暂存区。 */
        PERSIST("persist"),
        /** 原子切换最后正确运行镜像。 */
        ACTIVATE("activate");

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
