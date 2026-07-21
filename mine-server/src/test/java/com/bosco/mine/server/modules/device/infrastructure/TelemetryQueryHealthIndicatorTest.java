package com.bosco.mine.server.modules.device.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验独立 TimescaleDB 查询连接能够影响服务健康状态。
 */
class TelemetryQueryHealthIndicatorTest {

    @Test
    void 查询通道正常时应报告健康() {
        TelemetryQueryConnection connection = mock(TelemetryQueryConnection.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(connection.jdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                TelemetryQueryHealthIndicator.VERIFY_REQUIRED_SCHEMA_SQL, Long.class
        )).thenReturn(1L);

        Health health = new TelemetryQueryHealthIndicator(connection).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("role", "read-only");
        assertThat(health.getDetails()).containsEntry("schema", "ready");
    }

    @Test
    void 查询通道不可用时应报告故障() {
        TelemetryQueryConnection connection = mock(TelemetryQueryConnection.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(connection.jdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                TelemetryQueryHealthIndicator.VERIFY_REQUIRED_SCHEMA_SQL, Long.class
        ))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));

        Health health = new TelemetryQueryHealthIndicator(connection).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
