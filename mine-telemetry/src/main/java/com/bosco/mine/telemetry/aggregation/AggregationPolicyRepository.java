package com.bosco.mine.telemetry.aggregation;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 按有界设备批次从 TimescaleDB 最后正确镜像读取聚合策略。
 */
public final class AggregationPolicyRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int MAXIMUM_BATCH_SIZE = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建聚合策略镜像读取仓储。
     *
     * @param dataSource Telemetry 时序数据库连接池
     */
    public AggregationPolicyRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(
                dataSource, "聚合策略数据源不能为空"));
    }

    /**
     * 一次数据库往返读取指定设备当前最后正确聚合策略。
     *
     * @param deviceIds 设备标识集合
     * @return 设备到类型安全聚合策略的不可变映射
     */
    public Map<UUID, AggregationPolicy> findByDeviceIds(Collection<UUID> deviceIds) {
        Objects.requireNonNull(deviceIds, "聚合策略读取设备集合不能为空");
        List<UUID> ids = deviceIds.stream()
                .map(deviceId -> Objects.requireNonNull(deviceId, "设备标识不能为空"))
                .distinct()
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        if (ids.size() > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("单批聚合策略读取设备数不能超过 1000");
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = """
                SELECT device_id, runtime_revision, snapshot_id,
                       values_json::TEXT AS values_json, content_hash
                  FROM telemetry_aggregation_policy
                 WHERE device_id IN (%s)
                 ORDER BY device_id
                """.formatted(placeholders);
        Map<UUID, AggregationPolicy> policies = new LinkedHashMap<>();
        jdbcTemplate.query(sql, resultSet -> {
            UUID deviceId = resultSet.getObject("device_id", UUID.class);
            try {
                Map<String, Object> values = jsonMapper.readValue(
                        resultSet.getString("values_json"), MAP_TYPE);
                policies.put(deviceId, AggregationPolicy.from(
                        resultSet.getObject("snapshot_id", UUID.class),
                        resultSet.getLong("runtime_revision"),
                        resultSet.getString("content_hash"),
                        values
                ));
            } catch (JacksonException | IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "设备聚合策略镜像无法安全加载: " + deviceId, exception);
            }
        }, ids.toArray());
        return Map.copyOf(policies);
    }
}
