package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验持久告警状态机的持续时间、抖动、恢复、冷却和乱序语义。
 */
class AlarmStateMachineTest {

    private static final UUID OCCURRENCE_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000111");
    private static final Instant START = Instant.parse("2026-07-17T04:00:00Z");

    private final AlarmStateMachine machine = new AlarmStateMachine(() -> OCCURRENCE_ID);

    @Test
    void 零秒触发应立即进入活动并只产生一个触发事件() {
        AlarmPolicy.Rule rule = rule(0, 60, 300);

        AlarmStateMachine.Transition transition = machine.advance(
                AlarmStateMachine.State.normal(), firing(), START, rule);

        assertThat(transition.state().status()).isEqualTo(AlarmStateMachine.Status.ACTIVE);
        assertThat(transition.state().occurrenceId()).isEqualTo(OCCURRENCE_ID);
        assertThat(transition.event().type()).isEqualTo(AlarmStateMachine.EventType.RAISED);
        assertThat(machine.advance(transition.state(), firing(), START.plusSeconds(1), rule)
                .event()).isNull();
    }

    @Test
    void 触发候选抖动应回退并在连续满足后触发() {
        AlarmPolicy.Rule rule = rule(30, 60, 300);
        AlarmStateMachine.State state = machine.advance(
                AlarmStateMachine.State.normal(), firing(), START, rule).state();

        state = machine.advance(state, firing(), START.plusSeconds(29), rule).state();
        assertThat(state.status()).isEqualTo(AlarmStateMachine.Status.FIRING_PENDING);
        state = machine.advance(state, clear(), START.plusSeconds(30), rule).state();
        assertThat(state.status()).isEqualTo(AlarmStateMachine.Status.NORMAL);
        state = machine.advance(state, firing(), START.plusSeconds(40), rule).state();
        AlarmStateMachine.Transition raised = machine.advance(
                state, firing(), START.plusSeconds(70), rule);

        assertThat(raised.state().status()).isEqualTo(AlarmStateMachine.Status.ACTIVE);
        assertThat(raised.event().type()).isEqualTo(AlarmStateMachine.EventType.RAISED);
    }

    @Test
    void 恢复候选被再次触发应取消并在持续恢复后进入冷却() {
        AlarmPolicy.Rule rule = rule(0, 60, 300);
        AlarmStateMachine.State active = machine.advance(
                AlarmStateMachine.State.normal(), firing(), START, rule).state();
        AlarmStateMachine.State recovering = machine.advance(
                active, clear(), START.plusSeconds(10), rule).state();
        assertThat(recovering.status())
                .isEqualTo(AlarmStateMachine.Status.RECOVERING_PENDING);
        active = machine.advance(recovering, firing(), START.plusSeconds(20), rule).state();
        assertThat(active.status()).isEqualTo(AlarmStateMachine.Status.ACTIVE);
        recovering = machine.advance(active, clear(), START.plusSeconds(30), rule).state();

        AlarmStateMachine.Transition recovered = machine.advance(
                recovering, clear(), START.plusSeconds(90), rule);

        assertThat(recovered.state().status()).isEqualTo(AlarmStateMachine.Status.COOLDOWN);
        assertThat(recovered.state().cooldownUntil()).isEqualTo(START.plusSeconds(390));
        assertThat(recovered.event().type()).isEqualTo(AlarmStateMachine.EventType.RECOVERED);
        assertThat(recovered.event().recoveryReason()).isEqualTo("CONDITION_CLEARED");
    }

    @Test
    void 冷却期应阻止再次触发且到期后重新开始新周期() {
        AlarmPolicy.Rule rule = rule(0, 0, 300);
        AlarmStateMachine.State active = machine.advance(
                AlarmStateMachine.State.normal(), firing(), START, rule).state();
        AlarmStateMachine.State cooldown = machine.advance(
                active, clear(), START.plusSeconds(10), rule).state();

        AlarmStateMachine.Transition blocked = machine.advance(
                cooldown, firing(), START.plusSeconds(309), rule);
        AlarmStateMachine.Transition next = machine.advance(
                blocked.state(), firing(), START.plusSeconds(310), rule);

        assertThat(blocked.event()).isNull();
        assertThat(blocked.state().status()).isEqualTo(AlarmStateMachine.Status.COOLDOWN);
        assertThat(next.event().type()).isEqualTo(AlarmStateMachine.EventType.RAISED);
        assertThat(next.state().raisedAt()).isEqualTo(START.plusSeconds(310));
    }

    @Test
    void 未知观测应保持候选而接收时间倒退不得推进任何状态() {
        AlarmPolicy.Rule rule = rule(30, 60, 300);
        AlarmStateMachine.State pending = machine.advance(
                AlarmStateMachine.State.normal(), firing(), START, rule).state();
        AlarmStateMachine.State held = machine.advance(
                pending, unknown(), START.plusSeconds(20), rule).state();
        AlarmStateMachine.State backwards = machine.advance(
                held, firing(), START.plusSeconds(19), rule).state();

        assertThat(held.status()).isEqualTo(AlarmStateMachine.Status.FIRING_PENDING);
        assertThat(held.candidateSince()).isEqualTo(START);
        assertThat(held.lastReceivedAt()).isEqualTo(START.plusSeconds(20));
        assertThat(backwards).isEqualTo(held);
    }

    private AlarmPolicy.Rule rule(int trigger, int recover, int cooldown) {
        return new AlarmPolicy.Rule(
                "temperature-high", "发动机高温",
                AlarmPolicy.SourceType.NUMERIC, AlarmPolicy.Severity.CRITICAL,
                trigger, recover, cooldown, true, "engine.temperature",
                AlarmPolicy.Operator.GT, new BigDecimal("95"), new BigDecimal("90"), null);
    }

    private AlarmEvaluation firing() {
        return new AlarmEvaluation(AlarmEvaluation.Outcome.FIRING, evidence("96"));
    }

    private AlarmEvaluation clear() {
        return new AlarmEvaluation(AlarmEvaluation.Outcome.CLEAR, evidence("89"));
    }

    private AlarmEvaluation unknown() {
        return new AlarmEvaluation(AlarmEvaluation.Outcome.UNKNOWN, null);
    }

    private AlarmEvidencePayload evidence(String value) {
        return new AlarmEvidencePayload(
                new BigDecimal(value), "GT", new BigDecimal("95"),
                new BigDecimal("90"), null);
    }
}
