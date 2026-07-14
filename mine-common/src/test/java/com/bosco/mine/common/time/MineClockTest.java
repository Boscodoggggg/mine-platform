package com.bosco.mine.common.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验业务时钟可在测试中固定时间和时区。
 */
class MineClockTest {

    @Test
    void 应返回固定的时间和时区() {
        Instant expected = Instant.parse("2026-07-14T03:00:00Z");
        ZoneId zoneId = ZoneId.of("Asia/Shanghai");
        MineClock clock = MineClock.fixed(expected, zoneId);

        assertThat(clock.instant()).isEqualTo(expected);
        assertThat(clock.zoneId()).isEqualTo(zoneId);
        assertThat(clock.asJavaClock().instant()).isEqualTo(expected);
    }
}
