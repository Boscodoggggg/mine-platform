package com.bosco.mine.server.modules.device.infrastructure;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 将 mine-server 的独立 TimescaleDB 查询连接纳入 Actuator 就绪状态。
 */
@Component("telemetryQuery")
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public final class TelemetryQueryHealthIndicator implements HealthIndicator {

    static final String VERIFY_REQUIRED_SCHEMA_SQL = """
            SELECT (SELECT COUNT(ingest_id) FROM telemetry_inbox WHERE FALSE)
                 + (SELECT COUNT(ingest_id) FROM telemetry_numeric WHERE FALSE)
                 + (SELECT COUNT(*) FROM telemetry_latest_state WHERE FALSE)
                 + (SELECT COUNT(*) FROM telemetry_retired_boot WHERE FALSE)
                 + (SELECT COUNT(*) FROM telemetry_latest_numeric WHERE FALSE)
                 + (SELECT COUNT(committed_ingest_id)
                    FROM telemetry_ingestion_watermark WHERE singleton_id = 1)
            """;

    private final TelemetryQueryConnection connection;

    /**
     * 创建遥测查询健康检查。
     *
     * @param connection 遥测时序库受管连接
     */
    public TelemetryQueryHealthIndicator(TelemetryQueryConnection connection) {
        this.connection = connection;
    }

    /**
     * 以只读轻量查询检查 TimescaleDB 必需迁移和表级读取权限。
     *
     * @return 查询通道健康状态
     */
    @Override
    public Health health() {
        try {
            Long result = connection.jdbcTemplate().queryForObject(
                    VERIFY_REQUIRED_SCHEMA_SQL, Long.class
            );
            return Long.valueOf(1L).equals(result)
                    ? Health.up()
                    .withDetail("role", "read-only")
                    .withDetail("schema", "ready")
                    .build()
                    : Health.down().withDetail("reason", "unexpected-result").build();
        } catch (DataAccessException exception) {
            return Health.down(exception).withDetail("role", "read-only").build();
        }
    }
}
