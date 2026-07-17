package com.bosco.mine.server.modules.alarm;

import com.bosco.mine.server.modules.alarm.repository.AlarmEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * 暴露告警消费结果和按固定等级聚合的活动告警数量。
 */
@Component
public final class AlarmMetrics {
    private static final String CONSUME_METRIC = "mine.server.alarm.consume";
    private static final String ACTIVE_METRIC = "mine.server.alarm.active";
    private static final String DISPOSITION_METRIC = "mine.server.alarm.disposition";

    private final Map<String, Counter> consumeCounters;
    private final Map<String, Counter> dispositionCounters;

    /**
     * 创建只使用固定结果和固定告警等级标签的指标。
     *
     * @param registry 指标注册表
     * @param repository 告警仓储
     */
    @Autowired
    public AlarmMetrics(MeterRegistry registry, AlarmEventRepository repository) {
        this(registry, Objects.requireNonNull(repository, "告警指标仓储不能为空")
                ::countActiveBySeverity);
    }

    private AlarmMetrics(MeterRegistry registry, ToLongFunction<String> activeCounter) {
        Objects.requireNonNull(registry, "告警指标注册表不能为空");
        Objects.requireNonNull(activeCounter, "活动告警计数器不能为空");
        this.consumeCounters = Map.of(
                "processed", consumeCounter(registry, "processed"),
                "duplicate", consumeCounter(registry, "duplicate"),
                "rejected", consumeCounter(registry, "rejected"),
                "requeued", consumeCounter(registry, "requeued")
        );
        Map<String, Counter> dispositions = new LinkedHashMap<>();
        for (DispositionAction action : DispositionAction.values()) {
            for (DispositionOutcome outcome : DispositionOutcome.values()) {
                dispositions.put(dispositionKey(action, outcome), Counter.builder(DISPOSITION_METRIC)
                        .description("告警确认与关闭处置结果")
                        .tag("action", action.tagValue)
                        .tag("outcome", outcome.tagValue)
                        .register(registry));
            }
        }
        this.dispositionCounters = Map.copyOf(dispositions);
        for (String severity : new String[]{"INFO", "WARNING", "CRITICAL"}) {
            Gauge.builder(ACTIVE_METRIC, activeCounter,
                            value -> value.applyAsLong(severity))
                    .description("当前尚未恢复的活动告警数量")
                    .tag("severity", severity)
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** 创建仅供不启用 RabbitMQ 的受控测试使用的无外部依赖指标。 */
    public static AlarmMetrics noOp() {
        return new AlarmMetrics(new SimpleMeterRegistry(), severity -> 0L);
    }

    /** 记录实际改变告警聚合并确认的消息。 */
    public void recordProcessed() {
        consumeCounters.get("processed").increment();
    }

    /** 记录被 Inbox 或告警周期幂等规则短路并确认的重复消息。 */
    public void recordDuplicate() {
        consumeCounters.get("duplicate").increment();
    }

    /** 记录判定为永久无效并拒绝的消息。 */
    public void recordRejected() {
        consumeCounters.get("rejected").increment();
    }

    /** 记录因暂时故障重新入队的消息。 */
    public void recordRequeued() {
        consumeCounters.get("requeued").increment();
    }

    /**
     * 记录固定动作和固定结果的告警处置。
     *
     * @param action 处置动作
     * @param outcome 处置结果
     */
    public void recordDisposition(DispositionAction action, DispositionOutcome outcome) {
        dispositionCounters.get(dispositionKey(
                Objects.requireNonNull(action, "告警处置动作不能为空"),
                Objects.requireNonNull(outcome, "告警处置结果不能为空"))).increment();
    }

    private Counter consumeCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(CONSUME_METRIC)
                .description("告警消息消费结果")
                .tag("outcome", outcome)
                .register(registry);
    }

    private String dispositionKey(DispositionAction action, DispositionOutcome outcome) {
        return action.name() + ':' + outcome.name();
    }

    /** 允许进入指标标签的固定处置动作。 */
    public enum DispositionAction {
        /** 确认告警。 */
        ACKNOWLEDGE("acknowledge"),
        /** 关闭告警。 */
        CLOSE("close");

        private final String tagValue;

        DispositionAction(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    /** 允许进入指标标签的固定处置结果。 */
    public enum DispositionOutcome {
        /** 首次处置成功。 */
        APPLIED("applied"),
        /** 已处置请求被幂等短路。 */
        DUPLICATE("duplicate"),
        /** 请求状态或参数不允许执行。 */
        INVALID("invalid"),
        /** 乐观锁冲突。 */
        CONFLICT("conflict"),
        /** 告警不存在。 */
        NOT_FOUND("not_found"),
        /** 非预期依赖失败。 */
        FAILED("failed");

        private final String tagValue;

        DispositionOutcome(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
