package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.telemetry.alarm.outbox.TelemetryOutboxRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 验证告警 Outbox 原子认领、租约和状态约束。
 */
@Testcontainers
class AlarmOutboxIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-telemetry-test-password");

    private static TelemetryOutboxRepository repository;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new TelemetryOutboxRepository(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void 应精确认领并在租约到期前拒绝其他实例() {
        UUID eventId = uuid(21);
        insertEvent(eventId, uuid(22), "alarm.raised");

        TelemetryOutboxRepository.ClaimedEvent claimed = repository.claimById(
                eventId, "telemetry-a", Duration.ofSeconds(30)).orElseThrow();

        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(repository.claimById(eventId, "telemetry-b", Duration.ofSeconds(30))).isEmpty();
        repository.markPublished(eventId, "telemetry-a");
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @Test
    void 过期发布租约应可由其他实例恢复() {
        UUID eventId = uuid(23);
        insertEvent(eventId, uuid(24), "alarm.recovered");
        repository.claimById(eventId, "telemetry-a", Duration.ofSeconds(30)).orElseThrow();
        jdbcTemplate.update("""
                UPDATE telemetry_outbox_event
                   SET locked_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                       lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                 WHERE event_id = ?
                """, eventId);

        TelemetryOutboxRepository.ClaimedEvent reclaimed = repository.claimById(
                eventId, "telemetry-b", Duration.ofSeconds(30)).orElseThrow();

        assertThat(reclaimed.attempts()).isEqualTo(2);
        repository.markFailed(
                eventId, "telemetry-b", Instant.now().plusSeconds(60), "暂时失败");
        assertThat(status(eventId)).isEqualTo("FAILED");
    }

    @Test
    void 数据库应拒绝未知事件类型() {
        assertThatThrownBy(() -> insertEvent(uuid(25), uuid(26), "alarm.unknown"))
                .hasMessageContaining("ck_telemetry_outbox_event_type");
    }

    private static void insertEvent(UUID eventId, UUID occurrenceId, String eventType) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_outbox_event (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version,
                    payload, payload_hash, headers, occurred_at
                ) VALUES (?, 'ALARM_OCCURRENCE', ?, ?, 1,
                    '{}'::JSONB, ?, '{}'::JSONB, CURRENT_TIMESTAMP)
                """, eventId, occurrenceId, eventType, "sha256:" + "a".repeat(64));
    }

    private static String status(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM telemetry_outbox_event WHERE event_id = ?",
                String.class, eventId);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("01980a3b-1200-7000-8000-%012d".formatted(suffix));
    }
}
