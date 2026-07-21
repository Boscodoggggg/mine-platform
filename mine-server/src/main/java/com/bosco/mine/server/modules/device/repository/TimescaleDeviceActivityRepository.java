package com.bosco.mine.server.modules.device.repository;

import com.bosco.mine.server.modules.device.infrastructure.TelemetryQueryConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 使用 TimescaleDB 持久读模型批量查询设备有效活动证据。
 */
@Repository
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class TimescaleDeviceActivityRepository implements DeviceActivityRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建生产活动证据仓储。
     *
     * @param connection 遥测只读连接
     */
    @Autowired
    public TimescaleDeviceActivityRepository(TelemetryQueryConnection connection) {
        this(connection.jdbcTemplate());
    }

    /**
     * 创建可测试的活动证据仓储。
     *
     * @param jdbcTemplate 遥测只读 JDBC 模板
     */
    TimescaleDeviceActivityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<UUID, ActivityEvidence> findByDeviceIds(Collection<UUID> deviceIds) {
        List<UUID> ids = deviceIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT device_id, first_seen_at, last_received_at
                FROM telemetry_latest_state
                WHERE device_id IN (%s)
                  AND first_seen_at IS NOT NULL
                  AND last_received_at IS NOT NULL
                """.formatted(placeholders);
        return jdbcTemplate.query(sql, resultSet -> {
            Map<UUID, ActivityEvidence> result = new LinkedHashMap<>();
            while (resultSet.next()) {
                Timestamp first = resultSet.getTimestamp("first_seen_at");
                Timestamp last = resultSet.getTimestamp("last_received_at");
                result.put(
                        resultSet.getObject("device_id", UUID.class),
                        new ActivityEvidence(first.toInstant(), last.toInstant())
                );
            }
            return Map.copyOf(result);
        }, ids.toArray());
    }
}
