package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.messaging.strategy.StrategyRuntimeChangedPayload;
import com.bosco.mine.telemetry.aggregation.AggregationPolicy;
import com.bosco.mine.telemetry.aggregation.AggregationPolicyRepository;
import com.bosco.mine.telemetry.alarm.AlarmPolicy;
import com.bosco.mine.telemetry.alarm.AlarmPolicyRepository;
import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import com.bosco.mine.telemetry.quality.DataQualityPolicy;
import com.bosco.mine.telemetry.quality.DataQualityPolicyRepository;
import com.bosco.mine.telemetry.storage.TelemetryBatchWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 TimescaleDB 校验策略镜像的原子切换、删除、修订防倒退和 Inbox 幂等。
 */
@Testcontainers
class StrategyPolicyRepositoryIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID FIRST_DEVICE_ID = UUID.randomUUID();
    private static final UUID SECOND_DEVICE_ID = UUID.randomUUID();
    private static final UUID ALARM_DEVICE_ID = UUID.randomUUID();
    private static final UUID RETENTION_DEVICE_ID = UUID.randomUUID();
    private static final UUID AGGREGATION_DEVICE_ID = UUID.randomUUID();
    private static StrategyPolicyRepository repository;
    private static DataQualityPolicyRepository policyReader;
    private static AlarmPolicyRepository alarmPolicyReader;
    private static AggregationPolicyRepository aggregationPolicyReader;
    private static JdbcTemplate jdbcTemplate;
    private static TelemetryBatchWriter batchWriter;
    private static final List<UUID> OBSERVED_OUTBOX_EVENTS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void 初始化策略镜像数据库() {
        DataSource dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new StrategyPolicyRepository(dataSource, OBSERVED_OUTBOX_EVENTS::add);
        policyReader = new DataQualityPolicyRepository(dataSource);
        alarmPolicyReader = new AlarmPolicyRepository(dataSource);
        aggregationPolicyReader = new AggregationPolicyRepository(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        batchWriter = new TelemetryBatchWriter(dataSource);
    }

    @Test
    void 应原子应用增量删除旧策略并拒绝修订号冲突() {
        UUID firstSnapshotId = UUID.randomUUID();
        UUID firstEventId = UUID.fromString("019f6e00-0000-7000-8000-000000000011");
        String firstPolicyHash = "sha256:" + "1".repeat(64);
        repository.stage(firstSnapshotId, List.of(
                new StrategyPolicyRepository.StagedPolicy(
                        FIRST_DEVICE_ID, "{\"staleAfterSeconds\":60}", firstPolicyHash)));
        StrategyRuntimeChangedPayload first = payload(
                firstSnapshotId, 1, 1, "sha256:" + "a".repeat(64));

        repository.finalizeApply(firstEventId, first);

        assertThat(repository.isEventProcessed(firstEventId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT values_json ->> 'staleAfterSeconds' FROM telemetry_data_quality_policy WHERE device_id = ?",
                String.class, FIRST_DEVICE_ID)).isEqualTo("60");
        Map<UUID, DataQualityPolicy> firstPolicies = policyReader.findByDeviceIds(Set.of(
                FIRST_DEVICE_ID, SECOND_DEVICE_ID
        ));
        assertThat(firstPolicies).containsOnlyKeys(FIRST_DEVICE_ID);
        assertThat(firstPolicies.get(FIRST_DEVICE_ID).staleAfterSeconds()).isEqualTo(60);
        assertThat(firstPolicies.get(FIRST_DEVICE_ID).runtimeRevision()).isEqualTo(1L);

        UUID secondSnapshotId = UUID.randomUUID();
        UUID secondEventId = UUID.fromString("019f6e00-0000-7000-8000-000000000012");
        repository.stage(secondSnapshotId, List.of(
                new StrategyPolicyRepository.StagedPolicy(
                        FIRST_DEVICE_ID, "{}", "sha256:" + "2".repeat(64)),
                new StrategyPolicyRepository.StagedPolicy(
                        SECOND_DEVICE_ID, "{\"futureToleranceSeconds\":5}",
                        "sha256:" + "3".repeat(64))
        ));
        StrategyRuntimeChangedPayload second = payload(
                secondSnapshotId, 2, 2, "sha256:" + "b".repeat(64));

        repository.finalizeApply(secondEventId, second);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telemetry_data_quality_policy WHERE device_id = ?",
                Long.class, FIRST_DEVICE_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT runtime_revision FROM telemetry_data_quality_policy WHERE device_id = ?",
                Long.class, SECOND_DEVICE_ID)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT runtime_revision FROM telemetry_strategy_runtime_revision
                 WHERE product_id = ? AND strategy_type = 'DATA_QUALITY'
                """, Long.class, PRODUCT_ID)).isEqualTo(2L);
        assertThat(policyReader.findByDeviceIds(Set.of(FIRST_DEVICE_ID, SECOND_DEVICE_ID)))
                .containsOnlyKeys(SECOND_DEVICE_ID)
                .extractingByKey(SECOND_DEVICE_ID)
                .satisfies(policy -> assertThat(policy.futureToleranceSeconds()).isEqualTo(5));

        UUID conflictingEventId = UUID.fromString("019f6e00-0000-7000-8000-000000000013");
        StrategyRuntimeChangedPayload conflict = payload(
                UUID.randomUUID(), 2, 0, "sha256:" + "c".repeat(64));
        assertThatThrownBy(() -> repository.finalizeApply(conflictingEventId, conflict))
                .isInstanceOf(PermanentStrategySnapshotException.class);
        assertThat(repository.isEventProcessed(conflictingEventId)).isFalse();
    }

    @Test
    void 告警和数据质量应使用独立修订水位与最后正确镜像() {
        UUID snapshotId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        repository.stage(snapshotId, List.of(new StrategyPolicyRepository.StagedPolicy(
                ALARM_DEVICE_ID, alarmValues(), "sha256:" + "4".repeat(64))));
        StrategyRuntimeChangedPayload payload = new StrategyRuntimeChangedPayload(
                snapshotId, PRODUCT_ID, "ALARM", 1, 1, "sha256:" + "d".repeat(64));

        repository.finalizeApply(eventId, payload);

        Map<UUID, AlarmPolicy> policies = alarmPolicyReader.findByDeviceIds(
                Set.of(ALARM_DEVICE_ID, SECOND_DEVICE_ID));
        assertThat(policies).containsOnlyKeys(ALARM_DEVICE_ID);
        assertThat(policies.get(ALARM_DEVICE_ID).runtimeRevision()).isEqualTo(1);
        assertThat(policies.get(ALARM_DEVICE_ID).rules()).singleElement().satisfies(rule -> {
            assertThat(rule.key()).isEqualTo("required-rpm");
            assertThat(rule.qualityRuleCode()).isEqualTo(
                    com.bosco.mine.telemetry.quality.QualityRuleCode.REQUIRED_MISSING);
        });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT runtime_revision FROM telemetry_strategy_runtime_revision
                 WHERE product_id = ? AND strategy_type = 'ALARM'
                """, Long.class, PRODUCT_ID)).isEqualTo(1L);
    }

    @Test
    void 数据保留应原子写入类型化最后正确镜像并支持显式删除() {
        UUID firstSnapshotId = UUID.randomUUID();
        UUID firstEventId = UUID.randomUUID();
        repository.stage(firstSnapshotId, List.of(new StrategyPolicyRepository.StagedPolicy(
                RETENTION_DEVICE_ID, retentionValues(true, "STANDARD", 90, 180),
                "sha256:" + "b".repeat(64))));
        StrategyRuntimeChangedPayload first = new StrategyRuntimeChangedPayload(
                firstSnapshotId, PRODUCT_ID, "RETENTION", 1, 1,
                "sha256:" + "c".repeat(64));

        repository.finalizeApply(firstEventId, first);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT enabled, retention_class, raw_telemetry_days,
                       quality_evidence_days, runtime_revision
                  FROM telemetry_retention_policy
                 WHERE device_id = ?
                """, RETENTION_DEVICE_ID)).containsAllEntriesOf(Map.of(
                "enabled", true,
                "retention_class", "STANDARD",
                "raw_telemetry_days", 90,
                "quality_evidence_days", 180,
                "runtime_revision", 1L
        ));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT runtime_revision FROM telemetry_strategy_runtime_revision
                 WHERE product_id = ? AND strategy_type = 'RETENTION'
                """, Long.class, PRODUCT_ID)).isEqualTo(1L);

        UUID secondSnapshotId = UUID.randomUUID();
        repository.stage(secondSnapshotId, List.of(new StrategyPolicyRepository.StagedPolicy(
                RETENTION_DEVICE_ID, "{}", "sha256:" + "d".repeat(64))));
        repository.finalizeApply(UUID.randomUUID(), new StrategyRuntimeChangedPayload(
                secondSnapshotId, PRODUCT_ID, "RETENTION", 2, 1,
                "sha256:" + "e".repeat(64)));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telemetry_retention_policy WHERE device_id = ?",
                Long.class, RETENTION_DEVICE_ID)).isZero();
    }

    @Test
    void 数据保留镜像应在数据库层拒绝缺字段和错误类型() {
        assertThatThrownBy(() -> insertRetentionPolicyJson(
                UUID.randomUUID(),
                """
                {"policy":{
                  "enabled":true,
                  "retentionClass":"STANDARD",
                  "rawTelemetryDays":90
                }}
                """))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRetentionPolicyJson(
                UUID.randomUUID(),
                """
                {"policy":{
                  "enabled":"true",
                  "retentionClass":"STANDARD",
                  "rawTelemetryDays":90,
                  "qualityEvidenceDays":180
                }}
                """))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 聚合策略应原子写入类型化最后正确镜像并支持显式删除() {
        UUID firstSnapshotId = UUID.randomUUID();
        repository.stage(firstSnapshotId, List.of(new StrategyPolicyRepository.StagedPolicy(
                AGGREGATION_DEVICE_ID, aggregationValues(true, 7_200),
                "sha256:" + "1".repeat(64))));
        repository.finalizeApply(UUID.randomUUID(), new StrategyRuntimeChangedPayload(
                firstSnapshotId, PRODUCT_ID, "AGGREGATION", 1, 1,
                "sha256:" + "2".repeat(64)));

        Map<UUID, AggregationPolicy> policies = aggregationPolicyReader.findByDeviceIds(
                Set.of(AGGREGATION_DEVICE_ID, SECOND_DEVICE_ID));
        assertThat(policies).containsOnlyKeys(AGGREGATION_DEVICE_ID);
        assertThat(policies.get(AGGREGATION_DEVICE_ID)).satisfies(policy -> {
            assertThat(policy.enabled()).isTrue();
            assertThat(policy.correctionWindowSeconds()).isEqualTo(7_200);
            assertThat(policy.runtimeRevision()).isEqualTo(1);
            assertThat(policy.rules()).containsExactly(
                    new AggregationPolicy.Rule("payload", 3_600),
                    new AggregationPolicy.Rule("speed", 300));
        });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT runtime_revision FROM telemetry_strategy_runtime_revision
                 WHERE product_id = ? AND strategy_type = 'AGGREGATION'
                """, Long.class, PRODUCT_ID)).isEqualTo(1L);

        UUID secondSnapshotId = UUID.randomUUID();
        repository.stage(secondSnapshotId, List.of(new StrategyPolicyRepository.StagedPolicy(
                AGGREGATION_DEVICE_ID, "{}", "sha256:" + "3".repeat(64))));
        repository.finalizeApply(UUID.randomUUID(), new StrategyRuntimeChangedPayload(
                secondSnapshotId, PRODUCT_ID, "AGGREGATION", 2, 1,
                "sha256:" + "4".repeat(64)));

        assertThat(aggregationPolicyReader.findByDeviceIds(Set.of(AGGREGATION_DEVICE_ID)))
                .isEmpty();
    }

    @Test
    void 聚合镜像应在数据库层拒绝非法桶和未知字段() {
        assertThatThrownBy(() -> insertAggregationPolicyJson(
                UUID.randomUUID(),
                """
                {"policy":{
                  "enabled":true,
                  "correctionWindowSeconds":7200,
                  "rules":[{"propertyId":"speed","bucketSeconds":120}]
                }}
                """))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertAggregationPolicyJson(
                UUID.randomUUID(),
                """
                {"policy":{
                  "enabled":true,
                  "correctionWindowSeconds":7200,
                  "rules":[{"propertyId":"speed","bucketSeconds":300,"sql":"DROP"}]
                }}
                """))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 告警规则删除禁用或语义变化时应在快照事务内恢复旧周期() {
        int observedBefore = OBSERVED_OUTBOX_EVENTS.size();
        UUID productId = UUID.randomUUID();
        UUID removedDevice = UUID.randomUUID();
        UUID disabledDevice = UUID.randomUUID();
        UUID changedDevice = UUID.randomUUID();
        UUID firstSnapshot = UUID.randomUUID();
        repository.stage(firstSnapshot, List.of(
                new StrategyPolicyRepository.StagedPolicy(
                        removedDevice, numericAlarmValues(true, "95", "90"),
                        "sha256:" + "5".repeat(64)),
                new StrategyPolicyRepository.StagedPolicy(
                        disabledDevice, numericAlarmValues(true, "95", "90"),
                        "sha256:" + "6".repeat(64)),
                new StrategyPolicyRepository.StagedPolicy(
                        changedDevice, numericAlarmValues(true, "95", "90"),
                        "sha256:" + "7".repeat(64))));
        repository.finalizeApply(
                UUID.fromString("019f6e00-0000-7000-8000-000000000021"),
                new StrategyRuntimeChangedPayload(
                        firstSnapshot, productId, "ALARM", 1, 3,
                        "sha256:" + "e".repeat(64)));
        Map<UUID, AlarmPolicy> policies = alarmPolicyReader.findByDeviceIds(
                Set.of(removedDevice, disabledDevice, changedDevice));
        batchWriter.write(List.of(
                        alarmSample("019f6e00-0000-7000-8000-000000000031", removedDevice),
                        alarmSample("019f6e00-0000-7000-8000-000000000032", disabledDevice),
                        alarmSample("019f6e00-0000-7000-8000-000000000033", changedDevice)),
                List.of(), policies, Map.of());

        UUID secondSnapshot = UUID.randomUUID();
        repository.stage(secondSnapshot, List.of(
                new StrategyPolicyRepository.StagedPolicy(
                        removedDevice, "{}", "sha256:" + "8".repeat(64)),
                new StrategyPolicyRepository.StagedPolicy(
                        disabledDevice, numericAlarmValues(false, "95", "90"),
                        "sha256:" + "9".repeat(64)),
                new StrategyPolicyRepository.StagedPolicy(
                        changedDevice, numericAlarmValues(true, "100", "92"),
                        "sha256:" + "a".repeat(64))));
        repository.finalizeApply(
                UUID.fromString("019f6e00-0000-7000-8000-000000000022"),
                new StrategyRuntimeChangedPayload(
                        secondSnapshot, productId, "ALARM", 2, 3,
                        "sha256:" + "f".repeat(64)));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM telemetry_alarm_runtime_state
                 WHERE device_id IN (?, ?, ?)
                """, Long.class, removedDevice, disabledDevice, changedDevice)).isZero();
        Map<String, Long> reasons = jdbcTemplate.query("""
                SELECT payload ->> 'recoveryReason' AS reason, count(*) AS amount
                  FROM telemetry_outbox_event
                 WHERE event_type = 'alarm.recovered'
                   AND payload ->> 'deviceId' IN (?, ?, ?)
                 GROUP BY payload ->> 'recoveryReason'
                """, resultSet -> {
                    Map<String, Long> values = new java.util.LinkedHashMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getString("reason"), resultSet.getLong("amount"));
                    }
                    return values;
                }, removedDevice.toString(), disabledDevice.toString(), changedDevice.toString());
        assertThat(reasons).containsOnly(
                Map.entry("RULE_REMOVED", 1L),
                Map.entry("RULE_DISABLED", 1L),
                Map.entry("RULE_CHANGED", 1L));
        assertThat(OBSERVED_OUTBOX_EVENTS.subList(
                observedBefore, OBSERVED_OUTBOX_EVENTS.size())).hasSize(3);
    }

    private void insertRetentionPolicyJson(UUID deviceId, String valuesJson) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_retention_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (?, ?, 1, ?, CAST(? AS JSONB), ?)
                """, deviceId, PRODUCT_ID, UUID.randomUUID(), valuesJson,
                "sha256:" + "f".repeat(64));
    }

    private void insertAggregationPolicyJson(UUID deviceId, String valuesJson) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_aggregation_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (?, ?, 1, ?, CAST(? AS JSONB), ?)
                """, deviceId, PRODUCT_ID, UUID.randomUUID(), valuesJson,
                "sha256:" + "f".repeat(64));
    }

    private StrategyRuntimeChangedPayload payload(
            UUID snapshotId,
            long revision,
            int itemCount,
            String hash
    ) {
        return new StrategyRuntimeChangedPayload(
                snapshotId, PRODUCT_ID, "DATA_QUALITY", revision, itemCount, hash);
    }

    private String alarmValues() {
        return """
                {"rules":[{
                  "key":"required-rpm",
                  "name":"转速缺失",
                  "sourceType":"QUALITY",
                  "severity":"WARNING",
                  "triggerDurationSeconds":30,
                  "recoverDurationSeconds":60,
                  "cooldownSeconds":300,
                  "enabled":true,
                  "propertyId":"engine.rpm",
                  "qualityRuleCode":"REQUIRED_MISSING"
                }]}
                """;
    }

    private String numericAlarmValues(boolean enabled, String threshold, String recoverThreshold) {
        return """
                {"rules":[{
                  "key":"engine-rpm-high",
                  "name":"发动机转速过高",
                  "sourceType":"NUMERIC",
                  "severity":"CRITICAL",
                  "triggerDurationSeconds":0,
                  "recoverDurationSeconds":0,
                  "cooldownSeconds":60,
                  "enabled":%s,
                  "propertyId":"engine.rpm",
                  "operator":"GT",
                  "threshold":%s,
                  "recoverThreshold":%s
                }]}
                """.formatted(enabled, threshold, recoverThreshold);
    }

    private String retentionValues(
            boolean enabled,
            String retentionClass,
            int rawDays,
            int evidenceDays
    ) {
        return """
                {"policy":{
                  "enabled":%s,
                  "retentionClass":"%s",
                  "rawTelemetryDays":%d,
                  "qualityEvidenceDays":%d
                }}
                """.formatted(enabled, retentionClass, rawDays, evidenceDays);
    }

    private String aggregationValues(boolean enabled, int correctionWindowSeconds) {
        return """
                {"policy":{
                  "enabled":%s,
                  "correctionWindowSeconds":%d,
                  "rules":[
                    {"propertyId":"payload","bucketSeconds":3600},
                    {"propertyId":"speed","bucketSeconds":300}
                  ]
                }}
                """.formatted(enabled, correctionWindowSeconds);
    }

    private TelemetrySample alarmSample(String eventId, UUID deviceId) {
        Instant at = Instant.parse("2026-07-17T04:00:00Z");
        return new TelemetrySample(
                UUID.fromString(eventId), deviceId, "haul-truck", "engine.rpm", 96D,
                Quality.GOOD, "boot-a", 1L, MessageKind.TELEMETRY, at, at, at);
    }
}
