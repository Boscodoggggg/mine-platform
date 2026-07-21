package com.bosco.mine.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 封装平台业务时间来源，避免业务代码直接依赖系统当前时间。
 */
public final class MineClock {

    private final Clock delegate;

    private MineClock(Clock delegate) {
        this.delegate = delegate;
    }

    /**
     * 创建生产环境使用的 UTC 系统时钟。
     *
     * @return UTC 系统时钟
     */
    public static MineClock systemUtc() {
        return new MineClock(Clock.system(ZoneId.of("UTC")));
    }

    /**
     * 创建固定时间的测试时钟。
     *
     * @param instant 固定时刻
     * @param zoneId  业务时区
     * @return 固定时钟
     */
    public static MineClock fixed(Instant instant, ZoneId zoneId) {
        Objects.requireNonNull(instant, "固定时刻不能为空");
        Objects.requireNonNull(zoneId, "时区不能为空");
        return new MineClock(Clock.fixed(instant, zoneId));
    }

    /**
     * 获取当前业务时刻。
     *
     * @return 当前时刻
     */
    public Instant instant() {
        return delegate.instant();
    }

    /**
     * 获取业务时区。
     *
     * @return 时区
     */
    public ZoneId zoneId() {
        return delegate.getZone();
    }

    /**
     * 获取与 Java 时间 API 兼容的时钟。
     *
     * @return Java 标准时钟
     */
    public Clock asJavaClock() {
        return delegate;
    }
}
