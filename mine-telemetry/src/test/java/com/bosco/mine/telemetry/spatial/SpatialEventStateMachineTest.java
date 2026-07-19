package com.bosco.mine.telemetry.spatial;

import com.bosco.mine.model.spatial.SpatialPoint;
import com.bosco.mine.model.spatial.SpatialZone;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证空间事件状态机的连续确认、跳过语义、停留和配置版本隔离。
 */
class SpatialEventStateMachineTest {

    private static final UUID ZONE_ID = UUID.fromString("019f7b78-fbe2-7efd-99a4-37a015010101");
    private static final UUID VERSION_A = UUID.fromString("019f7b78-fbe2-7efd-99a4-37a015010102");
    private static final UUID VERSION_B = UUID.fromString("019f7b78-fbe2-7efd-99a4-37a015010103");
    private static final UUID TAG_ID = UUID.fromString("019f7b78-fbe2-7efd-99a4-37a015010104");
    private static final UUID VEHICLE_ID = UUID.fromString("019f7b78-fbe2-7efd-99a4-37a015010105");

    private final SpatialEventStateMachine machine = new SpatialEventStateMachine();

    @Test
    void 进入和离开都应要求连续确认且边界会打断候选() {
        SpatialEventStateMachine.ZoneConfiguration zone = zone(VERSION_A, 2, 2, 30, null, null);
        SpatialEventStateMachine.ZoneState state = null;

        state = evaluate(zone, state, fact(1, 20, 0, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        state = evaluate(zone, state, fact(2, 20, 0, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        assertThat(state.stableState()).isEqualTo(SpatialEventStateMachine.StableState.OUTSIDE);
        state = evaluate(zone, state, fact(3, 0, 0, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        SpatialEventStateMachine.TransitionResult boundary = evaluate(
                zone, state, fact(4, 10, 0, SpatialEventStateMachine.LocationQuality.GOOD));
        assertThat(boundary.skipReason()).isEqualTo(SpatialEventStateMachine.SkipReason.BOUNDARY);
        assertThat(boundary.state().candidateCount()).isZero();

        state = evaluate(zone, boundary.state(),
                fact(5, 0, 0, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        SpatialEventStateMachine.TransitionResult entered = evaluate(
                zone, state, fact(6, 0, 0, SpatialEventStateMachine.LocationQuality.GOOD));
        assertThat(entered.events())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.type()).isEqualTo(SpatialEventStateMachine.EventType.ENTER);
                    assertThat(event.confirmationPoints()).isEqualTo(2);
                });

        state = evaluate(zone, entered.state(),
                fact(7, 20, 0, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        SpatialEventStateMachine.TransitionResult exited = evaluate(
                zone, state, fact(8, 20, 0, SpatialEventStateMachine.LocationQuality.GOOD));
        assertThat(exited.events())
                .singleElement()
                .extracting(SpatialEventStateMachine.EmittedEvent::type)
                .isEqualTo(SpatialEventStateMachine.EventType.EXIT);
        assertThat(exited.state().enteredAt()).isNull();
    }

    @Test
    void 降级点和缺少高度应跳过并打断候选确认() {
        SpatialEventStateMachine.ZoneConfiguration zone = zone(VERSION_A, 2, 2, 30, 0D, 5D);
        SpatialEventStateMachine.ZoneState state = evaluate(
                zone, null, fact(1, 0, 2D, SpatialEventStateMachine.LocationQuality.GOOD)).state();

        SpatialEventStateMachine.TransitionResult degraded = evaluate(
                zone, state, fact(2, 0, 2D, SpatialEventStateMachine.LocationQuality.DEGRADED));
        assertThat(degraded.skipReason()).isEqualTo(SpatialEventStateMachine.SkipReason.DEGRADED);
        assertThat(degraded.state().candidateCount()).isZero();

        state = evaluate(zone, degraded.state(),
                fact(3, 0, 2D, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        SpatialEventStateMachine.TransitionResult heightUnknown = evaluate(
                zone, state, fact(4, 0, null, SpatialEventStateMachine.LocationQuality.GOOD));
        assertThat(heightUnknown.skipReason())
                .isEqualTo(SpatialEventStateMachine.SkipReason.HEIGHT_UNKNOWN);
        assertThat(heightUnknown.state().candidateCount()).isZero();
        assertThat(heightUnknown.events()).isEmpty();
    }

    @Test
    void 每次进入只能发出一次停留且离开重入后可以再次发出() {
        SpatialEventStateMachine.ZoneConfiguration zone = zone(VERSION_A, 1, 1, 30, null, null);
        SpatialEventStateMachine.ZoneState outside = evaluate(
                zone, null, fact(1, 20, 0, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        SpatialEventStateMachine.TransitionResult entered = evaluate(
                zone, outside, fact(2, 0, 0, SpatialEventStateMachine.LocationQuality.GOOD));
        Instant enteredAt = entered.state().enteredAt();

        SpatialEventStateMachine.TransitionResult beforeDwell = machine.evaluate(
                zone, entered.state(), factAt(3, 0, 0, enteredAt.plusSeconds(29),
                        SpatialEventStateMachine.LocationQuality.GOOD, VEHICLE_ID, null));
        assertThat(beforeDwell.events()).isEmpty();
        SpatialEventStateMachine.TransitionResult dwell = machine.evaluate(
                zone, beforeDwell.state(), factAt(4, 0, 0, enteredAt.plusSeconds(30),
                        SpatialEventStateMachine.LocationQuality.GOOD, VEHICLE_ID, null));
        assertThat(dwell.events())
                .singleElement()
                .extracting(SpatialEventStateMachine.EmittedEvent::type)
                .isEqualTo(SpatialEventStateMachine.EventType.DWELL);
        assertThat(machine.evaluate(
                zone, dwell.state(), factAt(5, 0, 0, enteredAt.plusSeconds(60),
                        SpatialEventStateMachine.LocationQuality.GOOD, VEHICLE_ID, null)).events())
                .isEmpty();

        SpatialEventStateMachine.TransitionResult exited = machine.evaluate(
                zone, dwell.state(), factAt(6, 20, 0, enteredAt.plusSeconds(61),
                        SpatialEventStateMachine.LocationQuality.GOOD, VEHICLE_ID, null));
        SpatialEventStateMachine.TransitionResult reentered = machine.evaluate(
                zone, exited.state(), factAt(7, 0, 0, enteredAt.plusSeconds(62),
                        SpatialEventStateMachine.LocationQuality.GOOD, VEHICLE_ID, null));
        assertThat(machine.evaluate(
                zone, reentered.state(), factAt(8, 0, 0, enteredAt.plusSeconds(92),
                        SpatialEventStateMachine.LocationQuality.GOOD, VEHICLE_ID, null)).events())
                .singleElement()
                .extracting(SpatialEventStateMachine.EmittedEvent::type)
                .isEqualTo(SpatialEventStateMachine.EventType.DWELL);
    }

    @Test
    void 配置版本切换不得继承旧区域状态() {
        SpatialEventStateMachine.ZoneConfiguration old = zone(
                VERSION_A, 1, 1, 30, null, null);
        SpatialEventStateMachine.ZoneState oldOutside = evaluate(
                old, null, fact(1, 20, 0, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        SpatialEventStateMachine.ZoneState oldInside = evaluate(
                old, oldOutside, fact(2, 0, 0, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        SpatialEventStateMachine.ZoneConfiguration replacement = zone(
                VERSION_B, 2, 2, 30, null, null);

        SpatialEventStateMachine.TransitionResult first = evaluate(
                replacement, oldInside,
                fact(3, 0, 0, SpatialEventStateMachine.LocationQuality.GOOD));
        assertThat(first.events()).isEmpty();
        assertThat(first.state().stableState()).isEqualTo(SpatialEventStateMachine.StableState.UNKNOWN);
        SpatialEventStateMachine.TransitionResult baseline = evaluate(
                replacement, first.state(),
                fact(4, 0, 0, SpatialEventStateMachine.LocationQuality.GOOD));
        assertThat(baseline.events()).isEmpty();
        assertThat(baseline.state().stableState())
                .isEqualTo(SpatialEventStateMachine.StableState.INSIDE);
        SpatialEventStateMachine.ZoneState exitCandidate = evaluate(
                replacement, baseline.state(),
                fact(5, 20, 0, SpatialEventStateMachine.LocationQuality.GOOD)).state();
        assertThat(evaluate(replacement, exitCandidate,
                fact(6, 20, 0, SpatialEventStateMachine.LocationQuality.GOOD)).events())
                .singleElement()
                .extracting(SpatialEventStateMachine.EmittedEvent::type)
                .isEqualTo(SpatialEventStateMachine.EventType.EXIT);
    }

    @Test
    void 未绑定标签仍应产生标签级事件且零秒停留表示关闭() {
        SpatialEventStateMachine.ZoneConfiguration zone = zone(VERSION_A, 1, 1, 0, null, null);
        SpatialEventStateMachine.ZoneState outside = machine.evaluate(
                zone, null, factAt(1, 20, 0, Instant.parse("2026-07-19T10:00:01Z"),
                        SpatialEventStateMachine.LocationQuality.GOOD, null, null)).state();
        SpatialEventStateMachine.TransitionResult entered = machine.evaluate(
                zone, outside, factAt(2, 0, 0, Instant.parse("2026-07-19T10:00:02Z"),
                        SpatialEventStateMachine.LocationQuality.GOOD, null, null));

        assertThat(entered.events())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.type()).isEqualTo(SpatialEventStateMachine.EventType.ENTER);
                    assertThat(event.tagId()).isEqualTo(TAG_ID);
                    assertThat(event.vehicleId()).isNull();
                    assertThat(event.personId()).isNull();
                });
        assertThat(machine.evaluate(
                zone, entered.state(), factAt(3, 0, 0, Instant.parse("2026-07-19T11:00:01Z"),
                        SpatialEventStateMachine.LocationQuality.GOOD, null, null)).events())
                .isEmpty();
    }

    private SpatialEventStateMachine.ZoneConfiguration zone(
            UUID versionId,
            int enterPoints,
            int exitPoints,
            int dwellSeconds,
            Double minZ,
            Double maxZ
    ) {
        return new SpatialEventStateMachine.ZoneConfiguration(
                ZONE_ID, versionId,
                new SpatialZone.Circle(0, 0, 10, 0.5, minZ, maxZ),
                enterPoints, exitPoints, dwellSeconds);
    }

    private SpatialEventStateMachine.TransitionResult evaluate(
            SpatialEventStateMachine.ZoneConfiguration zone,
            SpatialEventStateMachine.ZoneState state,
            SpatialEventStateMachine.LocationFact fact
    ) {
        return machine.evaluate(zone, state, fact);
    }

    private SpatialEventStateMachine.LocationFact fact(
            long sequence,
            double x,
            Number z,
            SpatialEventStateMachine.LocationQuality quality
    ) {
        return factAt(sequence, x, z,
                Instant.parse("2026-07-19T10:00:00Z").plusSeconds(sequence),
                quality, VEHICLE_ID, null);
    }

    private SpatialEventStateMachine.LocationFact factAt(
            long sequence,
            double x,
            Number z,
            Instant occurredAt,
            SpatialEventStateMachine.LocationQuality quality,
            UUID vehicleId,
            UUID personId
    ) {
        return new SpatialEventStateMachine.LocationFact(
                UUID.randomUUID(), TAG_ID, vehicleId, personId,
                new SpatialPoint(x, 0, z == null ? null : z.doubleValue()),
                quality, occurredAt, sequence);
    }
}
