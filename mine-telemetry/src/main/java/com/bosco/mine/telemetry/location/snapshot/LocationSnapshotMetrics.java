package com.bosco.mine.telemetry.location.snapshot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;

/**
 * 只使用固定阶段和结果标签记录定位运行快照处理证据。
 */
public final class LocationSnapshotMetrics {

    private static final String STAGE_METRIC = "mine.telemetry.location.snapshot.stage";
    private static final String RESULT_METRIC = "mine.telemetry.location.snapshot.result";

    private final MeterRegistry registry;

    /**
     * 创建低基数定位快照指标记录器。
     *
     * @param registry Micrometer 指标注册表
     */
    public LocationSnapshotMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "定位快照指标注册表不能为空");
    }

    /**
     * 记录固定阶段结果。
     *
     * @param stage 固定阶段
     * @param result 成功或失败
     */
    public void stage(Stage stage, Result result) {
        Counter.builder(STAGE_METRIC)
                .description("Telemetry 定位运行快照固定阶段处理结果")
                .tags("stage", stage.tag, "result", result.tag)
                .register(registry)
                .increment();
    }

    /**
     * 记录快照事件最终结果。
     *
     * @param outcome 固定最终结果
     */
    public void complete(Outcome outcome) {
        Counter.builder(RESULT_METRIC)
                .description("Telemetry 定位运行快照事件最终结果")
                .tag("outcome", outcome.tag)
                .register(registry)
                .increment();
    }

    /** 定位快照固定阶段。 */
    public enum Stage {
        /** 从控制面分页获取。 */
        FETCH("fetch"),
        /** 暂存规范化条目。 */
        STAGE("stage"),
        /** 校验整包和业务语义。 */
        VERIFY("verify"),
        /** 原子激活更高修订。 */
        ACTIVATE("activate"),
        /** 服务启动恢复最后正确版本。 */
        RESTORE("restore");

        private final String tag;

        Stage(String tag) {
            this.tag = tag;
        }
    }

    /** 固定阶段结果。 */
    public enum Result {
        /** 阶段成功。 */
        SUCCESS("success"),
        /** 阶段失败。 */
        FAILURE("failure");

        private final String tag;

        Result(String tag) {
            this.tag = tag;
        }
    }

    /** 定位快照事件固定最终结果。 */
    public enum Outcome {
        /** 快照已加载或幂等确认。 */
        LOADED("loaded"),
        /** 永久契约错误。 */
        PERMANENT_FAILURE("permanent_failure"),
        /** 暂时网络或存储错误。 */
        TRANSIENT_FAILURE("transient_failure"),
        /** 消息信封无法安全解析。 */
        INVALID_MESSAGE("invalid_message");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }
}
