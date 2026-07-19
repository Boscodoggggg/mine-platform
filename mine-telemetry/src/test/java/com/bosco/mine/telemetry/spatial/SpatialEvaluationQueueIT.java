package com.bosco.mine.telemetry.spatial;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 锁语义验证空间评估队列的确定性排序、租约和退避。
 */
@Testcontainers
class SpatialEvaluationQueueIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID ORGANIZATION_ID = UUID.fromString("019f7b66-ec84-72b5-bd8b-a32d92810101");
    private static final UUID SITE_ID = UUID.fromString("019f7b66-ec84-72b5-bd8b-a32d92810102");
    private static final UUID TAG_ID = UUID.fromString("019f7b66-ec84-72b5-bd8b-a32d92810103");
    private static final UUID SOURCE_DEVICE_ID = UUID.fromString("019f7b66-ec84-72b5-bd8b-a32d92810104");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static JdbcTemplate jdbc;
    private static SpatialEvaluationQueue queue;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        queue = new SpatialEvaluationQueue(dataSource,
                new SpatialWatermarkPolicy(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("""
                TRUNCATE spatial_event_outbox, spatial_event, spatial_zone_state,
                    spatial_evaluation_queue, spatial_subject_watermark,
                    spatial_replay_request, location_inbox CASCADE
                """);
    }

    @Test
    void 应按事件时间和摄入序号处理乱序点并跳过过晚点() {
        Instant arrivedAt = Instant.parse("2026-07-19T10:00:21Z");
        UUID event10 = enqueue(10, "2026-07-19T10:00:10Z", arrivedAt);
        UUID event07 = enqueue(11, "2026-07-19T10:00:07Z", arrivedAt.plusSeconds(1));
        enqueue(12, "2026-07-19T10:00:20Z", arrivedAt.plusSeconds(2));

        SpatialEvaluationQueue.ClaimedItem claimed07 = queue.claimReady(
                "worker-a", arrivedAt.plusSeconds(3), Duration.ofSeconds(10), 10).getFirst();
        assertThat(claimed07.locationEventId()).isEqualTo(event07);
        queue.markProcessed(
                event07, "worker-a", claimed07.leaseToken(), arrivedAt.plusSeconds(4));
        assertThat(queue.claimReady("worker-a", arrivedAt.plusSeconds(5),
                Duration.ofSeconds(10), 10))
                .extracting(SpatialEvaluationQueue.ClaimedItem::locationEventId)
                .containsExactly(event10);

        UUID tooLate = enqueue(13, "2026-07-19T10:00:14.999Z", arrivedAt.plusSeconds(6));
        assertThat(status(tooLate)).isEqualTo("SKIPPED:TOO_LATE");
    }

    @Test
    void 同一事件时间应按摄入序号排序且重复事件不得二次入队() {
        Instant arrivedAt = Instant.parse("2026-07-19T10:00:00Z");
        UUID first = enqueue(20, "2026-07-19T10:00:00Z", arrivedAt);
        UUID second = enqueue(21, "2026-07-19T10:00:00Z", arrivedAt.plusMillis(1));

        assertThat(queue.enqueue(command(first, 20,
                Instant.parse("2026-07-19T10:00:00Z"), arrivedAt.plusSeconds(1)))).isFalse();
        SpatialEvaluationQueue.ClaimedItem claimed = queue.claimReady(
                "worker-a", arrivedAt.plusSeconds(31), Duration.ofSeconds(10), 10).getFirst();
        assertThat(claimed.locationEventId()).isEqualTo(first);
        queue.markProcessed(first, "worker-a", claimed.leaseToken(), arrivedAt.plusSeconds(32));
        assertThat(queue.claimReady("worker-a", arrivedAt.plusSeconds(33),
                Duration.ofSeconds(10), 10))
                .extracting(SpatialEvaluationQueue.ClaimedItem::locationEventId)
                .containsExactly(second);
    }

    @Test
    void 空闲刷新应排出剩余点且过期租约可恢复() {
        Instant arrivedAt = Instant.parse("2026-07-19T11:00:00Z");
        UUID eventId = enqueue(30, "2026-07-19T11:00:00Z", arrivedAt);

        assertThat(queue.claimReady("worker-a", arrivedAt.plusSeconds(29),
                Duration.ofSeconds(10), 10)).isEmpty();
        SpatialEvaluationQueue.ClaimedItem expired = queue.claimReady(
                "worker-a", arrivedAt.plusSeconds(30), Duration.ofSeconds(10), 10).getFirst();
        assertThat(queue.claimReady("worker-b", arrivedAt.plusSeconds(39),
                Duration.ofSeconds(10), 10)).isEmpty();
        assertThat(queue.claimReady("worker-b", arrivedAt.plusSeconds(41),
                Duration.ofSeconds(10), 10))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.locationEventId()).isEqualTo(eventId);
                    assertThat(item.attempts()).isEqualTo(2);
                    assertThat(item.leaseToken()).isNotEqualTo(expired.leaseToken());
                });
    }

    @Test
    void 空闲刷新后的事件时间前沿应拒绝回退且旧租约令牌不能完成新租约() {
        Instant arrivedAt = Instant.parse("2026-07-19T11:30:00Z");
        UUID eventId = enqueue(35, "2026-07-19T11:30:00Z", arrivedAt);
        SpatialEvaluationQueue.ClaimedItem expired = queue.claimReady(
                "worker-a", arrivedAt.plusSeconds(30), Duration.ofSeconds(10), 1).getFirst();
        SpatialEvaluationQueue.ClaimedItem reclaimed = queue.claimReady(
                "worker-a", arrivedAt.plusSeconds(41), Duration.ofSeconds(10), 1).getFirst();

        assertThatThrownBy(() -> queue.markProcessed(
                eventId, "worker-a", expired.leaseToken(), arrivedAt.plusSeconds(42)))
                .isInstanceOf(IllegalStateException.class);
        queue.markProcessed(
                eventId, "worker-a", reclaimed.leaseToken(), arrivedAt.plusSeconds(42));

        UUID lateAfterFlush = enqueue(36, "2026-07-19T11:29:59Z", arrivedAt.plusSeconds(43));
        assertThat(status(lateAfterFlush)).isEqualTo("SKIPPED:TOO_LATE");
    }

    @Test
    void 失败应使用有界指数退避并在达到上限后死信() {
        Instant arrivedAt = Instant.parse("2026-07-19T12:00:00Z");
        UUID eventId = enqueue(40, "2026-07-19T12:00:00Z", arrivedAt);
        SpatialEvaluationQueue.ClaimedItem first = queue.claimReady(
                "worker-a", arrivedAt.plusSeconds(30), Duration.ofSeconds(10), 1).getFirst();

        queue.markFailed(eventId, "worker-a", first.leaseToken(), arrivedAt.plusSeconds(31),
                Duration.ofSeconds(5), Duration.ofSeconds(20), 2, "EVALUATION_ERROR");
        assertThat(queue.claimReady("worker-a", arrivedAt.plusSeconds(35),
                Duration.ofSeconds(10), 1)).isEmpty();
        SpatialEvaluationQueue.ClaimedItem second = queue.claimReady(
                "worker-a", arrivedAt.plusSeconds(36), Duration.ofSeconds(10), 1).getFirst();
        queue.markFailed(eventId, "worker-a", second.leaseToken(), arrivedAt.plusSeconds(37),
                Duration.ofSeconds(5), Duration.ofSeconds(20), 2, "EVALUATION_ERROR");

        assertThat(status(eventId)).isEqualTo("DEAD:EVALUATION_ERROR");
    }

    /**
     * 写入定位 Inbox 后把事实加入待评估队列。
     */
    private UUID enqueue(long ingestSequence, String occurredAt, Instant arrivedAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO location_inbox (
                    event_id, source_device_id, boot_id, source_sequence, payload_hash,
                    occurred_at, received_at, result, ingest_sequence
                ) VALUES (?, ?, 'boot-spatial', ?, ?, ?, ?, 'STORED', ?)
                """, eventId, SOURCE_DEVICE_ID, ingestSequence,
                "sha256:" + "a".repeat(64), Timestamp.from(Instant.parse(occurredAt)),
                Timestamp.from(arrivedAt), ingestSequence);
        assertThat(queue.enqueue(command(eventId, ingestSequence,
                Instant.parse(occurredAt), arrivedAt))).isTrue();
        return eventId;
    }

    /**
     * 创建固定矿区和标签作用域的入队命令。
     */
    private SpatialEvaluationQueue.EnqueueCommand command(
            UUID eventId, long ingestSequence, Instant occurredAt, Instant arrivedAt) {
        return new SpatialEvaluationQueue.EnqueueCommand(
                eventId, ORGANIZATION_ID, SITE_ID, TAG_ID,
                occurredAt, ingestSequence, 7, arrivedAt);
    }

    /**
     * 查询队列状态及固定结果原因。
     */
    private String status(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT status || ':' || COALESCE(result_reason, '')
                FROM spatial_evaluation_queue
                WHERE location_event_id = ?
                """, String.class, eventId);
    }
}
