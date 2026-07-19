package com.bosco.mine.simulator.location;

import java.time.Instant;
import java.util.Objects;

/**
 * 根据固定种子和发送序号生成可重复的二维矿区路线及受控异常位置。
 *
 * <p>生成器不保存游标，任意一个序号都能独立重算，因此周期发送、单发和测试重放
 * 得到相同结果。速度和航向均由相邻坐标与发送间隔计算，不使用互相矛盾的随机值。</p>
 */
public final class LocationRouteGenerator {

    private static final int CIRCLE_STEPS = 120;
    private static final int DWELL_STEPS = 120;
    private static final int POLYLINE_SEGMENT_STEPS = 30;
    private static final double[][] POLYLINE = {
            {100D, 100D}, {500D, 100D}, {500D, 400D}, {100D, 400D}
    };

    private final long seed;

    /**
     * 创建确定性路线生成器。
     *
     * @param seed 固定场景种子
     */
    public LocationRouteGenerator(long seed) {
        this.seed = seed;
    }

    /**
     * 生成一帧位置样本。
     *
     * @param scenario 模拟场景
     * @param sequence 运行时发送序号
     * @param timestamp 本次运行时发送时间
     * @param intervalMillis 周期发送间隔
     * @param defaultTagCode 正常场景使用的标签编码
     * @return 坐标、时间、顺序和运动学数据自洽的位置样本
     */
    public Sample generate(
            Scenario scenario,
            long sequence,
            Instant timestamp,
            long intervalMillis,
            String defaultTagCode
    ) {
        Objects.requireNonNull(scenario, "定位模拟场景不能为空");
        Objects.requireNonNull(timestamp, "定位模拟时间不能为空");
        if (sequence < 1L) {
            throw new IllegalArgumentException("定位模拟序号必须大于 0");
        }
        if (intervalMillis < 1L) {
            throw new IllegalArgumentException("定位模拟发送间隔必须大于 0");
        }
        if (defaultTagCode == null || defaultTagCode.isBlank()) {
            throw new IllegalArgumentException("定位模拟标签编码不能为空");
        }

        long sourceSequence = effectiveSequence(scenario, sequence);
        long offsetMillis = Math.multiplyExact(sequence - sourceSequence, intervalMillis);
        Instant occurredAt = timestamp.minusMillis(offsetMillis);
        if (scenario == Scenario.LATE && sequence % 5L == 0L) {
            occurredAt = occurredAt.minusSeconds(90L);
        }
        Coordinate coordinate = coordinate(scenario, sourceSequence);
        Coordinate previous = coordinate(scenario, Math.max(1L, sourceSequence - 1L));
        double deltaX = coordinate.x - previous.x;
        double deltaY = coordinate.y - previous.y;
        double intervalSeconds = intervalMillis / 1000D;
        double speed = sourceSequence == 1L
                ? 0D : round(Math.hypot(deltaX, deltaY) / intervalSeconds);
        double heading = sourceSequence == 1L || (deltaX == 0D && deltaY == 0D)
                ? 0D : normalizeHeading(Math.toDegrees(Math.atan2(deltaX, deltaY)));
        String tagCode = scenario == Scenario.UNBOUND_TAG
                ? "SIM-UNBOUND-%03d".formatted(Math.floorMod(seed, 1000L))
                : defaultTagCode.strip();

        return new Sample(
                scenario,
                sourceSequence,
                occurredAt,
                coordinate.x,
                coordinate.y,
                coordinate.z,
                speed,
                round(heading),
                scenario == Scenario.LOW_QUALITY ? 50D : 0.8D,
                tagCode);
    }

    private long effectiveSequence(Scenario scenario, long sequence) {
        if (scenario == Scenario.DUPLICATE && sequence > 1L && sequence % 5L == 0L) {
            return sequence - 1L;
        }
        if (scenario == Scenario.OUT_OF_ORDER && sequence > 2L && sequence % 5L == 0L) {
            return sequence - 2L;
        }
        return sequence;
    }

    private Coordinate coordinate(Scenario scenario, long sequence) {
        if (scenario == Scenario.POLYLINE) {
            return polyline(sequence);
        }
        if (scenario == Scenario.CROSS_BOUNDARY || scenario == Scenario.LATE) {
            return crossBoundary(sequence);
        }
        if (scenario == Scenario.BOUNDARY_JITTER) {
            return boundaryJitter(sequence);
        }
        if (scenario == Scenario.DWELL) {
            return dwell(sequence);
        }
        if (scenario == Scenario.OUT_OF_BOUNDS) {
            return new Coordinate(1_000_000D + sequence, -1_000_000D - sequence, 6D);
        }
        return circle(sequence);
    }

    private Coordinate crossBoundary(long sequence) {
        long step = Math.floorMod(sequence - 1L, 12L);
        double x = step < 3L || step >= 9L ? 480D : 520D;
        return new Coordinate(x, 400D, 6D);
    }

    private Coordinate boundaryJitter(long sequence) {
        double offset = sequence % 2L == 0L ? 0.2D : -0.2D;
        return new Coordinate(500D + offset, 400D, 6D);
    }

    private Coordinate dwell(long sequence) {
        long step = Math.floorMod(sequence - 1L, DWELL_STEPS);
        double x = step < 3L || step >= 90L ? 480D : 520D;
        return new Coordinate(x, 400D, 6D);
    }

    private Coordinate circle(long sequence) {
        double seedPhase = Math.floorMod(seed, CIRCLE_STEPS) * Math.PI * 2D / CIRCLE_STEPS;
        double phase = seedPhase + Math.floorMod(sequence - 1L, CIRCLE_STEPS)
                * Math.PI * 2D / CIRCLE_STEPS;
        return new Coordinate(
                round(500D + 120D * Math.cos(phase)),
                round(400D + 80D * Math.sin(phase)),
                6D);
    }

    private Coordinate polyline(long sequence) {
        long routeStep = Math.floorMod(sequence - 1L,
                (long) POLYLINE.length * POLYLINE_SEGMENT_STEPS);
        int segment = Math.toIntExact(routeStep / POLYLINE_SEGMENT_STEPS);
        double progress = (routeStep % POLYLINE_SEGMENT_STEPS) / (double) POLYLINE_SEGMENT_STEPS;
        double[] start = POLYLINE[segment];
        double[] end = POLYLINE[(segment + 1) % POLYLINE.length];
        return new Coordinate(
                round(start[0] + (end[0] - start[0]) * progress),
                round(start[1] + (end[1] - start[1]) * progress),
                6D);
    }

    private double normalizeHeading(double value) {
        double normalized = value % 360D;
        return normalized < 0D ? normalized + 360D : normalized;
    }

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    /** 定位模拟场景，枚举值同时作为模板中的稳定标识。 */
    public enum Scenario {
        /** 正常圆形路线。 */
        CIRCLE,
        /** 正常矿区道路折线路线。 */
        POLYLINE,
        /** 精度持续较差但坐标仍合法。 */
        LOW_QUALITY,
        /** 每五帧重复上一帧的序号、时间和坐标。 */
        DUPLICATE,
        /** 每五帧回退两个序号和发生时间。 */
        OUT_OF_ORDER,
        /** 使用需预先建档但不绑定车辆或人员的固定标签编码。 */
        UNBOUND_TAG,
        /** 生成明显超出正常矿区边界的坐标。 */
        OUT_OF_BOUNDS,
        /** 穿过固定模拟边界后再离开，用于进入和离开事件。 */
        CROSS_BOUNDARY,
        /** 在固定模拟边界容差带两侧交替，用于验证消抖。 */
        BOUNDARY_JITTER,
        /** 进入固定模拟区域后持续静止，用于单次停留事件。 */
        DWELL,
        /** 每五帧保持序号递增但回退发生时间，用于迟到排序。 */
        LATE
    }

    /**
     * 一帧确定性模拟位置。
     *
     * @param scenario 场景标识
     * @param sourceSequence 上报到平台的来源序号
     * @param occurredAt 设备发生时间
     * @param x 来源坐标 X
     * @param y 来源坐标 Y
     * @param z 来源坐标 Z
     * @param speedMetersPerSecond 由相邻点计算的速度
     * @param headingDegrees 由相邻点计算的航向
     * @param accuracyMeters 定位精度
     * @param tagCode 标签编码
     */
    public record Sample(
            Scenario scenario,
            long sourceSequence,
            Instant occurredAt,
            double x,
            double y,
            double z,
            double speedMetersPerSecond,
            double headingDegrees,
            double accuracyMeters,
            String tagCode
    ) {
    }

    private record Coordinate(double x, double y, double z) {
    }
}
