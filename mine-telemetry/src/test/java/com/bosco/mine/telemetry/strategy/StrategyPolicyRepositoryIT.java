package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.messaging.strategy.StrategyRuntimeChangedPayload;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
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
    private static StrategyPolicyRepository repository;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void 初始化策略镜像数据库() {
        DataSource dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new StrategyPolicyRepository(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
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

        UUID conflictingEventId = UUID.fromString("019f6e00-0000-7000-8000-000000000013");
        StrategyRuntimeChangedPayload conflict = payload(
                UUID.randomUUID(), 2, 0, "sha256:" + "c".repeat(64));
        assertThatThrownBy(() -> repository.finalizeApply(conflictingEventId, conflict))
                .isInstanceOf(PermanentStrategySnapshotException.class);
        assertThat(repository.isEventProcessed(conflictingEventId)).isFalse();
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
}
