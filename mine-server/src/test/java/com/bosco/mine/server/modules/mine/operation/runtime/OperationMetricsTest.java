package com.bosco.mine.server.modules.mine.operation.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 验证生产运营指标覆盖关键运行事实，且只使用固定低基数标签。
 */
class OperationMetricsTest {

    @Test
    void 应暴露队列死信关账遗留和固定运行结果() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger backlog = new AtomicInteger(18);
        AtomicInteger outboxDead = new AtomicInteger(2);
        AtomicInteger closeLeftovers = new AtomicInteger(3);
        OperationMetrics metrics = new OperationMetrics(
                registry, backlog::doubleValue, outboxDead::doubleValue,
                closeLeftovers::doubleValue);

        metrics.queue(OperationMetrics.Result.SUCCESS, OperationMetrics.Reason.NONE);
        metrics.queue(OperationMetrics.Result.SKIPPED, OperationMetrics.Reason.TOO_LATE);
        metrics.stage(OperationMetrics.Stage.AUTOMATION,
                OperationMetrics.Result.SUCCESS, OperationMetrics.Reason.NONE);
        metrics.stage(OperationMetrics.Stage.AUTOMATION,
                OperationMetrics.Result.FAILURE, OperationMetrics.Reason.STATE_ADVANCE);
        metrics.candidate(OperationMetrics.CandidateType.OUT_OF_ORDER,
                OperationMetrics.Reason.TOO_LATE);
        metrics.candidate("CUSTOM-CANDIDATE-42", "device-001-secret-error");
        metrics.record(OperationMetrics.WorkType.HAULAGE, OperationMetrics.Result.SUCCESS);

        assertThat(registry.get("mine.server.operation.queue.events")
                .tags("result", "skipped", "reason", "too_late")
                .counter().count()).isOne();
        assertThat(registry.get("mine.server.operation.stage.events")
                .tags("stage", "automation", "result", "failure",
                        "reason", "state_advance")
                .counter().count()).isOne();
        assertThat(registry.get("mine.server.operation.candidates")
                .tags("candidateType", "out_of_order", "reason", "too_late")
                .counter().count()).isOne();
        assertThat(registry.get("mine.server.operation.candidates")
                .tags("candidateType", "unknown", "reason", "unknown")
                .counter().count()).isOne();
        assertThat(registry.get("mine.server.operation.records")
                .tags("workType", "haulage", "result", "success")
                .counter().count()).isOne();
        assertThat(registry.get("mine.server.operation.queue.backlog")
                .gauge().value()).isEqualTo(18D);
        assertThat(registry.get("mine.server.operation.outbox.dead")
                .gauge().value()).isEqualTo(2D);
        assertThat(registry.get("mine.server.operation.shift.close.leftovers")
                .gauge().value()).isEqualTo(3D);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn(Set.of(
                                "stage", "result", "reason",
                                "workType", "candidateType"))));
    }

    @Test
    void 指标注册或更新失败不得中断运营业务处理() {
        SimpleMeterRegistry failingRegistry = new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("forced counter failure");
            }

            @Override
            protected <T> io.micrometer.core.instrument.Gauge newGauge(
                    Meter.Id id,
                    T object,
                    ToDoubleFunction<T> valueFunction
            ) {
                throw new IllegalStateException("forced gauge failure");
            }
        };

        OperationMetrics[] metrics = new OperationMetrics[1];
        assertThatCode(() -> metrics[0] = new OperationMetrics(
                failingRegistry, () -> 0D, () -> 0D, () -> 0D))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics[0].queue(
                OperationMetrics.Result.SUCCESS, OperationMetrics.Reason.NONE))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics[0].stage(
                OperationMetrics.Stage.AUTOMATION,
                OperationMetrics.Result.FAILURE,
                OperationMetrics.Reason.STATE_ADVANCE))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics[0].candidate(
                OperationMetrics.CandidateType.UNEXPECTED_EVENT,
                OperationMetrics.Reason.UNEXPECTED_EVENT))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics[0].record(
                OperationMetrics.WorkType.HAULAGE,
                OperationMetrics.Result.SUCCESS))
                .doesNotThrowAnyException();
    }

    @Test
    void 事务中的计数应只在业务提交后生效() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperationMetrics metrics = new OperationMetrics(
                registry, () -> 0D, () -> 0D, () -> 0D);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.stage(OperationMetrics.Stage.AUTOMATION,
                    OperationMetrics.Result.SUCCESS, OperationMetrics.Reason.NONE);

            assertThat(registry.find("mine.server.operation.stage.events").counter()).isNull();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(registry.get("mine.server.operation.stage.events")
                    .tags("stage", "automation", "result", "success", "reason", "none")
                    .counter().count()).isOne();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
