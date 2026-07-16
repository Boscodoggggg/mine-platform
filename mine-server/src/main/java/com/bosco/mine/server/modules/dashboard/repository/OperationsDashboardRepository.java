package com.bosco.mine.server.modules.dashboard.repository;

import com.bosco.mine.server.modules.device.infrastructure.TelemetryQueryConnection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 分别读取业务库设备台账和遥测库运行数据，由应用层完成跨库聚合。
 */
@Repository
public class OperationsDashboardRepository {

    private final JdbcTemplate businessJdbcTemplate;
    private final ObjectProvider<TelemetryQueryConnection> telemetryConnectionProvider;

    /**
     * 创建调度首页查询仓库。
     *
     * @param businessJdbcTemplate       业务库查询模板
     * @param telemetryConnectionProvider 遥测库只读连接提供者
     */
    public OperationsDashboardRepository(
            JdbcTemplate businessJdbcTemplate,
            ObjectProvider<TelemetryQueryConnection> telemetryConnectionProvider
    ) {
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.telemetryConnectionProvider = telemetryConnectionProvider;
    }

    /**
     * 查询全部启用设备的首页摘要。
     *
     * @return 启用设备摘要
     */
    public List<DashboardDevice> findEnabledDevices() {
        return businessJdbcTemplate.query(
                """
                SELECT id, device_code, name
                FROM dev_device
                WHERE status = 'ENABLED'
                ORDER BY device_code, id
                """,
                (resultSet, rowNumber) -> new DashboardDevice(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("device_code"),
                        resultSet.getString("name")
                )
        );
    }

    /**
     * 在同一个遥测只读快照中读取设备最后上报时间、今日消息和趋势。
     *
     * @param todayStart 今日起点
     * @param trendStart 趋势起点
     * @return 遥测统计快照
     * @throws IllegalStateException 遥测查询能力未启用或查询失败
     */
    public TelemetrySnapshot readTelemetry(Instant todayStart, Instant trendStart) {
        TelemetryQueryConnection connection = telemetryConnectionProvider.getIfAvailable();
        if (connection == null) {
            throw new IllegalStateException("遥测查询能力未启用");
        }
        return connection.readOnlyTransaction().execute(status -> readTelemetrySnapshot(
                connection.jdbcTemplate(), todayStart, trendStart
        ));
    }

    private TelemetrySnapshot readTelemetrySnapshot(
            JdbcTemplate telemetryJdbcTemplate,
            Instant todayStart,
            Instant trendStart
    ) {
        Map<UUID, Instant> lastSeenByDevice = new LinkedHashMap<>();
        telemetryJdbcTemplate.query(
                "SELECT device_id, updated_at FROM telemetry_latest_state",
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> lastSeenByDevice.put(
                        resultSet.getObject("device_id", UUID.class),
                        resultSet.getTimestamp("updated_at").toInstant()
                )
        );
        Long todayMessages = telemetryJdbcTemplate.queryForObject(
                "SELECT count(*) FROM telemetry_inbox WHERE received_at >= ?",
                Long.class,
                Timestamp.from(todayStart)
        );
        Instant latestReceivedAt = telemetryJdbcTemplate.query(
                "SELECT max(received_at) AS latest_received_at FROM telemetry_inbox",
                resultSet -> resultSet.next() && resultSet.getTimestamp("latest_received_at") != null
                        ? resultSet.getTimestamp("latest_received_at").toInstant()
                        : null
        );
        List<HourCount> hourly = telemetryJdbcTemplate.query(
                """
                SELECT date_trunc('hour', received_at) AS bucket, count(*) AS message_count
                FROM telemetry_inbox
                WHERE received_at >= ?
                GROUP BY bucket
                ORDER BY bucket
                """,
                (resultSet, rowNumber) -> new HourCount(
                        resultSet.getTimestamp("bucket").toInstant(),
                        resultSet.getLong("message_count")
                ),
                Timestamp.from(trendStart)
        );
        return new TelemetrySnapshot(
                Map.copyOf(lastSeenByDevice),
                todayMessages == null ? 0L : todayMessages,
                latestReceivedAt,
                List.copyOf(hourly)
        );
    }

    /**
     * 首页所需设备摘要。
     *
     * @param id         设备标识
     * @param deviceCode 设备编码
     * @param name       设备名称
     */
    public record DashboardDevice(UUID id, String deviceCode, String name) {
    }

    /**
     * 遥测库统计快照。
     *
     * @param lastSeenByDevice 设备最后上报时间
     * @param todayMessages    今日接收消息数
     * @param latestReceivedAt 最近消息接收时间
     * @param hourly           逐小时消息数
     */
    public record TelemetrySnapshot(
            Map<UUID, Instant> lastSeenByDevice,
            long todayMessages,
            Instant latestReceivedAt,
            List<HourCount> hourly
    ) {
    }

    /**
     * 单小时消息计数。
     *
     * @param hour  小时起点
     * @param count 消息数量
     */
    public record HourCount(Instant hour, long count) {
    }
}
