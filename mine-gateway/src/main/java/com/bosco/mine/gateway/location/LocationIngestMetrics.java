package com.bosco.mine.gateway.location;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;

/**
 * 使用固定结果和原因标签记录 Gateway 定位接入结论。
 */
public final class LocationIngestMetrics {

    private static final String METRIC_NAME = "mine.gateway.location.ingest";
    private static final LocationIngestMetrics NOOP = new LocationIngestMetrics();

    private final MeterRegistry registry;

    private LocationIngestMetrics() {
        this.registry = null;
    }

    /**
     * 创建低基数定位接入指标。
     *
     * @param registry Micrometer 指标注册表
     */
    public LocationIngestMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "定位接入指标注册表不能为空");
    }

    /**
     * 记录一条原始定位消息的最终处理结论。
     *
     * @param result 固定处理结果
     * @param reason 固定处理原因
     */
    public void record(Result result, Reason reason) {
        Objects.requireNonNull(result, "定位接入结果不能为空");
        Objects.requireNonNull(reason, "定位接入原因不能为空");
        if (registry == null) {
            return;
        }
        Counter.builder(METRIC_NAME)
                .description("Gateway 原始定位消息接入结果")
                .tags("result", result.tag, "reason", reason.tag)
                .register(registry)
                .increment();
    }

    /** @return 不注册指标的兼容实例 */
    static LocationIngestMetrics noop() {
        return NOOP;
    }

    /** Gateway 定位接入固定结果。 */
    public enum Result {
        /** 已可靠发布并确认原消息。 */
        ACCEPTED("accepted"),
        /** 永久非法并拒绝原消息。 */
        REJECTED("rejected"),
        /** 暂时失败并重新投递。 */
        REQUEUED("requeued");

        private final String tag;

        Result(String tag) {
            this.tag = tag;
        }
    }

    /** Gateway 定位接入固定原因。 */
    public enum Reason {
        /** 正常处理。 */
        NONE("none"),
        /** 凭据、主题或载荷不合法。 */
        INVALID_MESSAGE("invalid_message"),
        /** 内部可靠发布暂时失败。 */
        PUBLISH_FAILURE("publish_failure");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }
    }
}
