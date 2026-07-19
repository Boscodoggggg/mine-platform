package com.bosco.mine.simulator.location;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证定位模拟路线在固定种子下可重复，并能显式制造受控异常场景。
 */
class LocationRouteGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-07-18T08:00:05Z");
    private final LocationRouteGenerator generator = new LocationRouteGenerator(20260718L);

    @Test
    void 固定种子圆形路线应生成自洽且可重复的位置运动学数据() {
        LocationRouteGenerator.Sample first = generator.generate(
                LocationRouteGenerator.Scenario.CIRCLE, 2L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample repeated = new LocationRouteGenerator(20260718L).generate(
                LocationRouteGenerator.Scenario.CIRCLE, 2L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample previous = generator.generate(
                LocationRouteGenerator.Scenario.CIRCLE, 1L, NOW.minusSeconds(1), 1000L, "tag-001");

        assertThat(first).isEqualTo(repeated);
        assertThat(first.sourceSequence()).isEqualTo(2L);
        assertThat(first.occurredAt()).isEqualTo(NOW);
        assertThat(first.tagCode()).isEqualTo("tag-001");
        assertThat(first.speedMetersPerSecond()).isCloseTo(
                Math.hypot(first.x() - previous.x(), first.y() - previous.y()),
                org.assertj.core.data.Offset.offset(0.002D));
        assertThat(first.headingDegrees()).isBetween(0D, 360D);
        assertThat(first.accuracyMeters()).isEqualTo(0.8D);
    }

    @Test
    void 折线路线应沿矿区道路折点连续运动() {
        LocationRouteGenerator.Sample first = generator.generate(
                LocationRouteGenerator.Scenario.POLYLINE, 1L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample next = generator.generate(
                LocationRouteGenerator.Scenario.POLYLINE, 2L, NOW.plusSeconds(1), 1000L, "tag-001");

        assertThat(first.y()).isEqualTo(next.y());
        assertThat(next.x()).isGreaterThan(first.x());
        assertThat(next.speedMetersPerSecond()).isPositive();
    }

    @Test
    void 异常场景应覆盖低质量重复乱序未绑定和越界() {
        LocationRouteGenerator.Sample lowQuality = generator.generate(
                LocationRouteGenerator.Scenario.LOW_QUALITY, 3L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample duplicatePrevious = generator.generate(
                LocationRouteGenerator.Scenario.DUPLICATE, 4L, NOW.minusSeconds(1), 1000L, "tag-001");
        LocationRouteGenerator.Sample duplicate = generator.generate(
                LocationRouteGenerator.Scenario.DUPLICATE, 5L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample outOfOrder = generator.generate(
                LocationRouteGenerator.Scenario.OUT_OF_ORDER, 5L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample unbound = generator.generate(
                LocationRouteGenerator.Scenario.UNBOUND_TAG, 3L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample outside = generator.generate(
                LocationRouteGenerator.Scenario.OUT_OF_BOUNDS, 3L, NOW, 1000L, "tag-001");

        assertThat(lowQuality.accuracyMeters()).isGreaterThanOrEqualTo(50D);
        assertThat(duplicate.sourceSequence()).isEqualTo(duplicatePrevious.sourceSequence());
        assertThat(duplicate.occurredAt()).isEqualTo(duplicatePrevious.occurredAt());
        assertThat(duplicate.x()).isEqualTo(duplicatePrevious.x());
        assertThat(duplicate.y()).isEqualTo(duplicatePrevious.y());
        assertThat(outOfOrder.sourceSequence()).isEqualTo(3L);
        assertThat(outOfOrder.occurredAt()).isEqualTo(NOW.minusSeconds(2));
        assertThat(unbound.tagCode()).startsWith("SIM-UNBOUND-");
        assertThat(Math.abs(outside.x())).isGreaterThan(100_000D);
        assertThat(Math.abs(outside.y())).isGreaterThan(100_000D);
    }

    @Test
    void 跨边界场景应稳定产生围栏外进入围栏内再离开的轨迹() {
        LocationRouteGenerator.Sample outside = generator.generate(
                LocationRouteGenerator.Scenario.CROSS_BOUNDARY, 2L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample inside = generator.generate(
                LocationRouteGenerator.Scenario.CROSS_BOUNDARY, 5L, NOW.plusSeconds(3),
                1000L, "tag-001");
        LocationRouteGenerator.Sample exited = generator.generate(
                LocationRouteGenerator.Scenario.CROSS_BOUNDARY, 11L, NOW.plusSeconds(9),
                1000L, "tag-001");

        assertThat(outside.x()).isLessThan(500D);
        assertThat(inside.x()).isGreaterThan(500D);
        assertThat(exited.x()).isLessThan(500D);
        assertThat(outside.y()).isEqualTo(inside.y()).isEqualTo(exited.y());
    }

    @Test
    void 边界抖动场景应在固定容差带两侧交替且保持可重复() {
        LocationRouteGenerator.Sample odd = generator.generate(
                LocationRouteGenerator.Scenario.BOUNDARY_JITTER, 3L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample even = generator.generate(
                LocationRouteGenerator.Scenario.BOUNDARY_JITTER, 4L, NOW.plusSeconds(1),
                1000L, "tag-001");

        assertThat(odd.x()).isBetween(499.5D, 500.5D);
        assertThat(even.x()).isBetween(499.5D, 500.5D);
        assertThat(odd.x()).isNotEqualTo(even.x());
        assertThat(new LocationRouteGenerator(20260718L).generate(
                LocationRouteGenerator.Scenario.BOUNDARY_JITTER, 3L, NOW,
                1000L, "tag-001")).isEqualTo(odd);
    }

    @Test
    void 停留场景进入后应保持静止并为单次停留事件提供连续确认点() {
        LocationRouteGenerator.Sample entered = generator.generate(
                LocationRouteGenerator.Scenario.DWELL, 5L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample dwelling = generator.generate(
                LocationRouteGenerator.Scenario.DWELL, 70L, NOW.plusSeconds(65),
                1000L, "tag-001");

        assertThat(entered.x()).isGreaterThan(500D);
        assertThat(dwelling.x()).isEqualTo(entered.x());
        assertThat(dwelling.y()).isEqualTo(entered.y());
        assertThat(dwelling.speedMetersPerSecond()).isZero();
        assertThat(Duration.between(entered.occurredAt(), dwelling.occurredAt()))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void 迟到场景应保持来源序号递增但周期性回退发生时间() {
        LocationRouteGenerator.Sample previous = generator.generate(
                LocationRouteGenerator.Scenario.LATE, 4L, NOW, 1000L, "tag-001");
        LocationRouteGenerator.Sample late = generator.generate(
                LocationRouteGenerator.Scenario.LATE, 5L, NOW.plusSeconds(1),
                1000L, "tag-001");

        assertThat(late.sourceSequence()).isEqualTo(5L);
        assertThat(late.occurredAt()).isBefore(previous.occurredAt());
        assertThat(Duration.between(late.occurredAt(), previous.occurredAt()).getSeconds())
                .isGreaterThanOrEqualTo(30L);
    }

    @Test
    void 非法序号间隔和标签应被拒绝() {
        assertThatThrownBy(() -> generator.generate(
                LocationRouteGenerator.Scenario.CIRCLE, 0L, NOW, 1000L, "tag-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");
        assertThatThrownBy(() -> generator.generate(
                LocationRouteGenerator.Scenario.CIRCLE, 1L, NOW, 0L, "tag-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("间隔");
        assertThatThrownBy(() -> generator.generate(
                LocationRouteGenerator.Scenario.CIRCLE, 1L, NOW, 1000L, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签");
    }
}
