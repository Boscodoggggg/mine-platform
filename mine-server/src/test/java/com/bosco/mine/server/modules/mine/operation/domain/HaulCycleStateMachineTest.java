package com.bosco.mine.server.modules.mine.operation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CandidateReason.OUT_OF_ORDER;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CandidateReason.UNEXPECTED_EVENT;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CandidateReason.VEHICLE_MISMATCH;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CandidateReason.ZONE_VERSION_MISMATCH;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CycleStatus.COMPLETED;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CycleStatus.HAULING;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CycleStatus.LOADED;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CycleStatus.LOADING;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CycleStatus.UNLOADING;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.CycleStatus.WAITING_LOAD;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.EventType.DWELL;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.EventType.ENTER;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.EventType.EXIT;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.EvidenceRole.LOAD_DWELL;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.EvidenceRole.LOAD_ENTER;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.EvidenceRole.LOAD_EXIT;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.EvidenceRole.UNLOAD_DWELL;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.EvidenceRole.UNLOAD_ENTER;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.Outcome.CANDIDATE;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.Outcome.DUPLICATE;
import static com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine.Outcome.TRANSITIONED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证一次装载、运输和卸载循环的纯状态推进。
 */
class HaulCycleStateMachineTest {

    private static final UUID VEHICLE_ID = UUID.fromString("019b0000-0000-7000-8000-000000000001");
    private static final UUID OTHER_VEHICLE_ID = UUID.fromString("019b0000-0000-7000-8000-000000000002");
    private static final UUID LOAD_ZONE_VERSION_ID = UUID.fromString("019b0000-0000-7000-8000-000000000011");
    private static final UUID UNLOAD_ZONE_VERSION_ID = UUID.fromString("019b0000-0000-7000-8000-000000000012");
    private static final UUID OTHER_ZONE_VERSION_ID = UUID.fromString("019b0000-0000-7000-8000-000000000013");
    private static final Instant START = Instant.parse("2026-07-19T00:00:00Z");

    private final HaulCycleStateMachine machine = new HaulCycleStateMachine();

    @Test
    void 完整空间证据应形成一趟运输循环() {
        HaulCycleStateMachine.CycleState state = initial();

        HaulCycleStateMachine.Evaluation loadEnter = evaluate(
                state, signal(1, VEHICLE_ID, LOAD_ZONE_VERSION_ID, ENTER, 1, 1));
        assertTransition(loadEnter, LOADING, LOAD_ENTER);

        HaulCycleStateMachine.Evaluation loadDwell = evaluate(
                loadEnter.state(), signal(2, VEHICLE_ID, LOAD_ZONE_VERSION_ID, DWELL, 61, 2));
        assertTransition(loadDwell, LOADED, LOAD_DWELL);

        HaulCycleStateMachine.Evaluation loadExit = evaluate(
                loadDwell.state(), signal(3, VEHICLE_ID, LOAD_ZONE_VERSION_ID, EXIT, 62, 3));
        assertTransition(loadExit, HAULING, LOAD_EXIT);

        HaulCycleStateMachine.Evaluation unloadEnter = evaluate(
                loadExit.state(), signal(4, VEHICLE_ID, UNLOAD_ZONE_VERSION_ID, ENTER, 120, 4));
        assertTransition(unloadEnter, UNLOADING, UNLOAD_ENTER);

        HaulCycleStateMachine.Evaluation unloadDwell = evaluate(
                unloadEnter.state(), signal(5, VEHICLE_ID, UNLOAD_ZONE_VERSION_ID, DWELL, 181, 5));
        assertTransition(unloadDwell, COMPLETED, UNLOAD_DWELL);
        assertThat(unloadDwell.state().evidence()).containsOnlyKeys(
                LOAD_ENTER, LOAD_DWELL, LOAD_EXIT, UNLOAD_ENTER, UNLOAD_DWELL);
    }

    @Test
    void 完成后应显式开始下一循环并保留顺序前沿() {
        HaulCycleStateMachine.CycleState completed = completeCycle();

        HaulCycleStateMachine.CycleState next = machine.nextCycle(completed);

        assertThat(next.status()).isEqualTo(WAITING_LOAD);
        assertThat(next.cycleNumber()).isEqualTo(2);
        assertThat(next.evidence()).isEmpty();
        assertThat(next.lastOccurredAt()).isEqualTo(completed.lastOccurredAt());
        assertThat(next.lastIngestSequence()).isEqualTo(completed.lastIngestSequence());
    }

    @Test
    void 已完成循环在显式开始下一循环前不得接受新证据() {
        HaulCycleStateMachine.CycleState completed = completeCycle();

        HaulCycleStateMachine.Evaluation result = evaluate(completed, signal(
                6, VEHICLE_ID, LOAD_ZONE_VERSION_ID, ENTER, 240, 6));

        assertCandidate(result, completed, UNEXPECTED_EVENT);
    }

    @Test
    void 未完成循环不得开始下一循环() {
        assertThatThrownBy(() -> machine.nextCycle(initial()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 从持久层重建的循环状态必须具备当前阶段完整证据前缀() {
        assertThatThrownBy(() -> new HaulCycleStateMachine.CycleState(
                COMPLETED, 1, VEHICLE_ID, LOAD_ZONE_VERSION_ID,
                UNLOAD_ZONE_VERSION_ID, null, 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HaulCycleStateMachine.CycleState(
                LOADING, 1, VEHICLE_ID, LOAD_ZONE_VERSION_ID,
                UNLOAD_ZONE_VERSION_ID, null, 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 从持久层重建的证据必须按发生顺序排列且前沿指向最后证据() {
        HaulCycleStateMachine.Evidence loadEnter = evidence(
                LOAD_ENTER, 1, 20, 2);
        HaulCycleStateMachine.Evidence loadDwell = evidence(
                LOAD_DWELL, 2, 10, 1);

        assertThatThrownBy(() -> new HaulCycleStateMachine.CycleState(
                LOADED, 1, VEHICLE_ID, LOAD_ZONE_VERSION_ID,
                UNLOAD_ZONE_VERSION_ID, loadDwell.occurredAt(),
                loadDwell.ingestSequence(), Map.of(
                        LOAD_ENTER, loadEnter,
                        LOAD_DWELL, loadDwell)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HaulCycleStateMachine.CycleState(
                LOADING, 1, VEHICLE_ID, LOAD_ZONE_VERSION_ID,
                UNLOAD_ZONE_VERSION_ID, START.plusSeconds(30), 3,
                Map.of(LOAD_ENTER, loadEnter)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 重复证据应幂等返回原状态() {
        HaulCycleStateMachine.SpatialSignal signal = signal(
                1, VEHICLE_ID, LOAD_ZONE_VERSION_ID, ENTER, 1, 1);
        HaulCycleStateMachine.Evaluation first = evaluate(initial(), signal);

        HaulCycleStateMachine.Evaluation duplicate = evaluate(first.state(), signal);

        assertThat(duplicate.outcome()).isEqualTo(DUPLICATE);
        assertThat(duplicate.state()).isSameAs(first.state());
        assertThat(duplicate.candidateReason()).isNull();
    }

    @Test
    void 车辆不匹配应进入固定候选且不改变状态() {
        HaulCycleStateMachine.CycleState state = initial();
        HaulCycleStateMachine.Evaluation result = evaluate(state, signal(
                1, OTHER_VEHICLE_ID, LOAD_ZONE_VERSION_ID, ENTER, 1, 1));

        assertCandidate(result, state, VEHICLE_MISMATCH);
    }

    @Test
    void 区域版本不匹配应进入固定候选且不改变状态() {
        HaulCycleStateMachine.CycleState state = initial();
        HaulCycleStateMachine.Evaluation result = evaluate(state, signal(
                1, VEHICLE_ID, OTHER_ZONE_VERSION_ID, ENTER, 1, 1));

        assertCandidate(result, state, ZONE_VERSION_MISMATCH);
    }

    @Test
    void 非预期步骤应进入候选而不能跳过装载停留() {
        HaulCycleStateMachine.Evaluation loading = evaluate(initial(), signal(
                1, VEHICLE_ID, LOAD_ZONE_VERSION_ID, ENTER, 1, 1));

        HaulCycleStateMachine.Evaluation result = evaluate(loading.state(), signal(
                2, VEHICLE_ID, LOAD_ZONE_VERSION_ID, EXIT, 2, 2));

        assertThat(result.state()).isSameAs(loading.state());
        assertThat(result.candidateReason()).isEqualTo(UNEXPECTED_EVENT);
    }

    @Test
    void 旧事件时间和同时间低摄入序号均不得让状态倒退() {
        HaulCycleStateMachine.Evaluation loading = evaluate(initial(), signal(
                1, VEHICLE_ID, LOAD_ZONE_VERSION_ID, ENTER, 10, 10));

        HaulCycleStateMachine.Evaluation olderTime = evaluate(loading.state(), signal(
                2, VEHICLE_ID, LOAD_ZONE_VERSION_ID, DWELL, 9, 11));
        HaulCycleStateMachine.Evaluation olderSequence = evaluate(loading.state(), signal(
                3, VEHICLE_ID, LOAD_ZONE_VERSION_ID, DWELL, 10, 9));

        assertCandidate(olderTime, loading.state(), OUT_OF_ORDER);
        assertCandidate(olderSequence, loading.state(), OUT_OF_ORDER);
    }

    private HaulCycleStateMachine.CycleState completeCycle() {
        HaulCycleStateMachine.CycleState state = initial();
        state = evaluate(state, signal(1, VEHICLE_ID, LOAD_ZONE_VERSION_ID, ENTER, 1, 1)).state();
        state = evaluate(state, signal(2, VEHICLE_ID, LOAD_ZONE_VERSION_ID, DWELL, 61, 2)).state();
        state = evaluate(state, signal(3, VEHICLE_ID, LOAD_ZONE_VERSION_ID, EXIT, 62, 3)).state();
        state = evaluate(state, signal(4, VEHICLE_ID, UNLOAD_ZONE_VERSION_ID, ENTER, 120, 4)).state();
        return evaluate(state, signal(
                5, VEHICLE_ID, UNLOAD_ZONE_VERSION_ID, DWELL, 181, 5)).state();
    }

    private HaulCycleStateMachine.CycleState initial() {
        return HaulCycleStateMachine.CycleState.initial(
                VEHICLE_ID, LOAD_ZONE_VERSION_ID, UNLOAD_ZONE_VERSION_ID);
    }

    private HaulCycleStateMachine.Evaluation evaluate(
            HaulCycleStateMachine.CycleState state,
            HaulCycleStateMachine.SpatialSignal signal
    ) {
        return machine.evaluate(state, signal);
    }

    private HaulCycleStateMachine.SpatialSignal signal(
            int eventSuffix,
            UUID vehicleId,
            UUID zoneVersionId,
            HaulCycleStateMachine.EventType eventType,
            long seconds,
            long ingestSequence
    ) {
        return new HaulCycleStateMachine.SpatialSignal(
                UUID.fromString("019b0000-0000-7000-8000-%012d".formatted(eventSuffix)),
                vehicleId, zoneVersionId, eventType, START.plusSeconds(seconds), ingestSequence);
    }

    private HaulCycleStateMachine.Evidence evidence(
            HaulCycleStateMachine.EvidenceRole role,
            int eventSuffix,
            long seconds,
            long ingestSequence
    ) {
        return new HaulCycleStateMachine.Evidence(
                role,
                UUID.fromString("019b0000-0000-7000-8000-%012d".formatted(eventSuffix)),
                START.plusSeconds(seconds),
                ingestSequence);
    }

    private void assertTransition(
            HaulCycleStateMachine.Evaluation evaluation,
            HaulCycleStateMachine.CycleStatus expectedStatus,
            HaulCycleStateMachine.EvidenceRole expectedEvidence
    ) {
        assertThat(evaluation.outcome()).isEqualTo(TRANSITIONED);
        assertThat(evaluation.state().status()).isEqualTo(expectedStatus);
        assertThat(evaluation.acceptedEvidence()).isEqualTo(expectedEvidence);
        assertThat(evaluation.candidateReason()).isNull();
    }

    private void assertCandidate(
            HaulCycleStateMachine.Evaluation evaluation,
            HaulCycleStateMachine.CycleState expectedState,
            HaulCycleStateMachine.CandidateReason expectedReason
    ) {
        assertThat(evaluation.outcome()).isEqualTo(CANDIDATE);
        assertThat(evaluation.state()).isSameAs(expectedState);
        assertThat(evaluation.candidateReason()).isEqualTo(expectedReason);
    }
}
