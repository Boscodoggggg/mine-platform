package com.bosco.mine.server.integration;

import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.operation.runtime.OperationEventQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 验证运营空间事件的幂等入队、有界排序和可恢复租约。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class OperationEventQueueIT {

    private static final Instant BASE_TIME = Instant.parse("2026-07-20T01:00:00Z");
    private static final OperationEventQueue.ClaimPolicy POLICY =
            new OperationEventQueue.ClaimPolicy(
                    Duration.ofSeconds(30), Duration.ofMinutes(1),
                    Duration.ofSeconds(30), 2,
                    Duration.ofSeconds(1), Duration.ofMinutes(1));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine_operation_event_queue")
            .withUsername("mine")
            .withPassword("mine-operation-event-test-password");

    @Autowired
    private OperationEventQueue queue;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID siteId;
    private UUID geofenceId;
    private UUID geofenceVersionId;
    private UUID firstVehicleId;
    private UUID secondVehicleId;
    private UUID firstTagId;
    private UUID secondTagId;

    @BeforeEach
    void 准备空间事件身份() {
        jdbcTemplate.execute("TRUNCATE TABLE mine_organization, sys_inbox_event CASCADE");
        organizationId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        geofenceId = UUID.randomUUID();
        geofenceVersionId = UUID.randomUUID();
        firstVehicleId = UUID.randomUUID();
        secondVehicleId = UUID.randomUUID();
        firstTagId = UUID.randomUUID();
        secondTagId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, 'operation-event-org', '运营事件集团')
                """, organizationId);
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, 'operation-event-site', '运营事件矿区', 'UTC')
                """, siteId, organizationId);
        insertVehicle(firstVehicleId, "operation-truck-a");
        insertVehicle(secondVehicleId, "operation-truck-b");
        insertTag(firstTagId, "operation-tag-a");
        insertTag(secondTagId, "operation-tag-b");
        insertPublishedGeofence();
    }

    @Test
    void 相同事件应规范化幂等且冲突重放必须拒绝() {
        RealtimeSpatialEvent event = event(
                uuidV7(1), firstVehicleId, firstTagId, BASE_TIME, 1);

        assertThat(queue.enqueue(event)).isEqualTo(OperationEventQueue.EnqueueResult.ACCEPTED);
        assertThat(queue.enqueue(event)).isEqualTo(OperationEventQueue.EnqueueResult.DUPLICATE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_inbox_event
                WHERE consumer_name = 'mine-server-operation-spatial' AND event_id = ?
                """, Long.class, event.eventId())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_spatial_event_queue
                WHERE event_id = ?
                  AND payload ->> 'eventId' = ?
                  AND payload -> 'evidence' ->> 'classification' = 'INSIDE'
                """, Long.class, event.eventId(), event.eventId().toString())).isOne();

        RealtimeSpatialEvent conflict = event(
                event.eventId(), firstVehicleId, firstTagId, BASE_TIME.plusSeconds(1), 2);
        assertThatThrownBy(() -> queue.enqueue(conflict))
                .isInstanceOf(OperationEventQueue.IdentityConflictException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_operation_spatial_event_queue WHERE event_id = ?",
                Long.class, event.eventId())).isOne();
    }

    @Test
    void 队列必须具备车辆和未解析标签顺序索引() {
        String definitions = String.join("\n", jdbcTemplate.queryForList("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'mine_operation_spatial_event_queue'
                ORDER BY indexname
                """, String.class));

        assertThat(definitions)
                .contains(
                        "(vehicle_id, occurred_at, ingest_sequence, event_id) "
                                + "WHERE (vehicle_id IS NOT NULL)",
                        "(location_tag_id, occurred_at, ingest_sequence, event_id) "
                                + "WHERE (vehicle_id IS NULL)");
    }

    @Test
    void 跨矿身份必须拒绝并回滚Inbox() {
        RealtimeSpatialEvent event = event(
                uuidV7(2), UUID.randomUUID(), firstTagId, BASE_TIME, 1);

        assertThatThrownBy(() -> queue.enqueue(event))
                .isInstanceOf(OperationEventQueue.IdentityConflictException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_inbox_event WHERE event_id = ?",
                Long.class, event.eventId())).isZero();
    }

    @Test
    void 应按事件时间和摄入序号排序并保持同车串行() {
        RealtimeSpatialEvent third = event(
                uuidV7(13), firstVehicleId, firstTagId, BASE_TIME.plusSeconds(20), 3);
        RealtimeSpatialEvent first = event(
                uuidV7(11), firstVehicleId, firstTagId, BASE_TIME, 1);
        RealtimeSpatialEvent second = event(
                uuidV7(12), firstVehicleId, firstTagId, BASE_TIME.plusSeconds(10), 2);
        queue.enqueue(third);
        queue.enqueue(first);
        queue.enqueue(second);
        makeIdle(third.eventId(), first.eventId(), second.eventId());

        OperationEventQueue.ClaimedEvent firstClaim = queue.claimNext(POLICY).orElseThrow();
        assertThat(firstClaim.eventId()).isEqualTo(first.eventId());
        assertThat(firstClaim.tooLate()).isFalse();
        assertThat(queue.claimNext(POLICY)).isEmpty();
        assertThat(queue.complete(firstClaim)).isTrue();

        OperationEventQueue.ClaimedEvent secondClaim = queue.claimNext(POLICY).orElseThrow();
        assertThat(secondClaim.eventId()).isEqualTo(second.eventId());
        assertThat(queue.complete(secondClaim)).isTrue();
        OperationEventQueue.ClaimedEvent thirdClaim = queue.claimNext(POLICY).orElseThrow();
        assertThat(thirdClaim.eventId()).isEqualTo(third.eventId());
    }

    @Test
    void 新事件应等待乱序窗口并在空闲后排空() {
        RealtimeSpatialEvent older = event(
                uuidV7(21), firstVehicleId, firstTagId, BASE_TIME, 1);
        RealtimeSpatialEvent newer = event(
                uuidV7(22), firstVehicleId, firstTagId, BASE_TIME.plusSeconds(40), 2);
        queue.enqueue(newer);
        queue.enqueue(older);

        OperationEventQueue.ClaimedEvent olderClaim = queue.claimNext(POLICY).orElseThrow();
        assertThat(olderClaim.eventId()).isEqualTo(older.eventId());
        assertThat(queue.complete(olderClaim)).isTrue();
        assertThat(queue.claimNext(POLICY)).isEmpty();

        makeIdle(newer.eventId());
        assertThat(queue.claimNext(POLICY).orElseThrow().eventId()).isEqualTo(newer.eventId());
    }

    @Test
    void 不同车辆可以并行认领且过期租约应更换随机令牌() {
        RealtimeSpatialEvent first = event(
                uuidV7(31), firstVehicleId, firstTagId, BASE_TIME, 1);
        RealtimeSpatialEvent second = event(
                uuidV7(32), secondVehicleId, secondTagId, BASE_TIME, 1);
        queue.enqueue(first);
        queue.enqueue(second);
        makeIdle(first.eventId(), second.eventId());

        List<OperationEventQueue.ClaimedEvent> claims = concurrentClaims(2).stream()
                .flatMap(Optional::stream)
                .toList();
        assertThat(claims).hasSize(2);
        OperationEventQueue.ClaimedEvent firstClaim = claims.stream()
                .filter(claim -> claim.vehicleId().equals(firstVehicleId))
                .findFirst()
                .orElseThrow();
        OperationEventQueue.ClaimedEvent parallelClaim = claims.stream()
                .filter(claim -> claim.vehicleId().equals(secondVehicleId))
                .findFirst()
                .orElseThrow();
        assertThat(parallelClaim.vehicleId()).isNotEqualTo(firstClaim.vehicleId());
        jdbcTemplate.update("""
                UPDATE mine_operation_spatial_event_queue
                SET leased_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, firstClaim.queueId());

        OperationEventQueue restartedQueue = new OperationEventQueue(jdbcTemplate);
        OperationEventQueue.ClaimedEvent reclaimed = restartedQueue.claimNext(POLICY).orElseThrow();
        assertThat(reclaimed.queueId()).isEqualTo(firstClaim.queueId());
        assertThat(reclaimed.leaseToken()).isNotEqualTo(firstClaim.leaseToken());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(queue.complete(firstClaim)).isFalse();
        assertThat(restartedQueue.complete(reclaimed)).isTrue();
    }

    @Test
    void 同一车辆并发认领只能获得一个有效租约() {
        RealtimeSpatialEvent first = event(
                uuidV7(33), firstVehicleId, firstTagId, BASE_TIME, 1);
        RealtimeSpatialEvent second = event(
                uuidV7(34), firstVehicleId, firstTagId, BASE_TIME.plusSeconds(1), 2);
        queue.enqueue(first);
        queue.enqueue(second);
        makeIdle(first.eventId(), second.eventId());

        List<Optional<OperationEventQueue.ClaimedEvent>> claims = concurrentClaims(2);

        assertThat(claims).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(claims.stream().flatMap(Optional::stream).findFirst().orElseThrow().eventId())
                .isEqualTo(first.eventId());
    }

    @Test
    void 失败应脱敏退避并在达到上限后进入DEAD() {
        RealtimeSpatialEvent event = event(
                uuidV7(41), firstVehicleId, firstTagId, BASE_TIME, 1);
        queue.enqueue(event);
        makeIdle(event.eventId());

        OperationEventQueue.ClaimedEvent firstClaim = queue.claimNext(POLICY).orElseThrow();
        assertThat(queue.fail(
                firstClaim, new IllegalStateException("password=mine-secret\nstack"), POLICY))
                .isEqualTo(OperationEventQueue.FailureResult.RETRY_SCHEDULED);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, last_error, next_attempt_at > CURRENT_TIMESTAMP AS delayed
                FROM mine_operation_spatial_event_queue WHERE id = ?
                """, firstClaim.queueId()))
                .containsEntry("status", "FAILED")
                .containsEntry("delayed", true)
                .satisfies(row -> assertThat(String.valueOf(row.get("last_error")))
                        .doesNotContain("mine-secret", "stack"));
        assertThat(queue.claimNext(POLICY)).isEmpty();

        jdbcTemplate.update("""
                UPDATE mine_operation_spatial_event_queue
                SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, firstClaim.queueId());
        OperationEventQueue.ClaimedEvent secondClaim = queue.claimNext(POLICY).orElseThrow();
        assertThat(queue.fail(secondClaim, new IllegalStateException("再次失败"), POLICY))
                .isEqualTo(OperationEventQueue.FailureResult.DEAD);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM mine_operation_spatial_event_queue WHERE id = ?
                """, String.class, firstClaim.queueId())).isEqualTo("DEAD");
    }

    @Test
    void 崩溃租约耗尽尝试次数后应进入DEAD并放行同车后续事件() {
        OperationEventQueue.ClaimPolicy singleAttemptPolicy =
                new OperationEventQueue.ClaimPolicy(
                        Duration.ZERO, Duration.ofMinutes(1),
                        Duration.ofSeconds(30), 1,
                        Duration.ofSeconds(1), Duration.ofMinutes(1));
        RealtimeSpatialEvent exhausted = event(
                uuidV7(61), firstVehicleId, firstTagId, BASE_TIME, 1);
        RealtimeSpatialEvent following = event(
                uuidV7(62), firstVehicleId, firstTagId, BASE_TIME.plusSeconds(10), 2);
        queue.enqueue(exhausted);
        queue.enqueue(following);
        makeIdle(exhausted.eventId(), following.eventId());

        OperationEventQueue.ClaimedEvent abandoned =
                queue.claimNext(singleAttemptPolicy).orElseThrow();
        jdbcTemplate.update("""
                UPDATE mine_operation_spatial_event_queue
                SET leased_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, abandoned.queueId());

        OperationEventQueue.ClaimedEvent next =
                queue.claimNext(singleAttemptPolicy).orElseThrow();

        assertThat(next.eventId()).isEqualTo(following.eventId());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, attempt_count, lease_token IS NULL AS lease_cleared
                FROM mine_operation_spatial_event_queue WHERE id = ?
                """, abandoned.queueId()))
                .containsEntry("status", "DEAD")
                .containsEntry("attempt_count", 1)
                .containsEntry("lease_cleared", true);
        assertThat(queue.complete(abandoned)).isFalse();
    }

    @Test
    void 落后最终处理前沿的事件必须标记TOO_LATE() {
        RealtimeSpatialEvent frontier = event(
                uuidV7(52), firstVehicleId, firstTagId, BASE_TIME.plusSeconds(10), 2);
        queue.enqueue(frontier);
        makeIdle(frontier.eventId());
        assertThat(queue.complete(queue.claimNext(POLICY).orElseThrow())).isTrue();

        RealtimeSpatialEvent late = event(
                uuidV7(51), firstVehicleId, firstTagId, BASE_TIME, 1);
        queue.enqueue(late);
        makeIdle(late.eventId());

        OperationEventQueue.ClaimedEvent claim = queue.claimNext(POLICY).orElseThrow();
        assertThat(claim.eventId()).isEqualTo(late.eventId());
        assertThat(claim.tooLate()).isTrue();
    }

    private void insertVehicle(UUID id, String code) {
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, ?, '测试矿卡', 'HAUL_TRUCK')
                """, id, organizationId, siteId, code);
    }

    private void insertTag(UUID id, String code) {
        jdbcTemplate.update("""
                INSERT INTO mine_location_tag (
                    id, organization_id, site_id, tag_code
                ) VALUES (?, ?, ?, ?)
                """, id, organizationId, siteId, code);
    }

    private void insertPublishedGeofence() {
        UUID coordinateSystemId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y, min_z, max_z
                ) VALUES (?, ?, ?, 'operation-coordinate', '运营坐标系',
                          'LOCAL_CARTESIAN', 'METER', TRUE,
                          -1000, 1000, -1000, 1000, -100, 100)
                """, coordinateSystemId, organizationId, siteId);
        jdbcTemplate.update("""
                INSERT INTO mine_geofence (
                    id, organization_id, site_id, geofence_code
                ) VALUES (?, ?, ?, 'operation-load-zone')
                """, geofenceId, organizationId, siteId);
        jdbcTemplate.update("""
                INSERT INTO mine_geofence_version (
                    id, geofence_id, organization_id, site_id,
                    coordinate_system_id, version_number, name, geofence_type,
                    boundary_tolerance_meters, enter_confirm_points,
                    exit_confirm_points, dwell_seconds,
                    allowed_lateness_seconds, idle_flush_seconds,
                    valid_from, status
                ) VALUES (?, ?, ?, ?, ?, 1, '运营装载区', 'LOAD',
                          1, 2, 2, 30, 30, 60,
                          TIMESTAMPTZ '2026-01-01 00:00:00Z', 'DRAFT')
                """, geofenceVersionId, geofenceId, organizationId, siteId,
                coordinateSystemId);
        jdbcTemplate.update("""
                INSERT INTO mine_geofence_vertex (
                    geofence_version_id, organization_id, site_id,
                    sequence_number, x, y
                ) VALUES
                    (?, ?, ?, 1, 0, 0),
                    (?, ?, ?, 2, 100, 0),
                    (?, ?, ?, 3, 0, 100)
                """, geofenceVersionId, organizationId, siteId,
                geofenceVersionId, organizationId, siteId,
                geofenceVersionId, organizationId, siteId);
        jdbcTemplate.update("""
                UPDATE mine_geofence_version
                SET status = 'PUBLISHED', content_hash = ?,
                    published_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, "sha256:" + "a".repeat(64), geofenceVersionId);
    }

    private RealtimeSpatialEvent event(
            UUID eventId,
            UUID vehicleId,
            UUID tagId,
            Instant occurredAt,
            long ingestSequence
    ) {
        return new RealtimeSpatialEvent(
                eventId, organizationId, siteId,
                RealtimeSpatialEvent.ZoneType.GEOFENCE,
                geofenceId, geofenceVersionId, tagId, vehicleId, null,
                RealtimeSpatialEvent.EventType.ENTER, UUID.randomUUID(),
                RealtimeSpatialEvent.StableState.OUTSIDE,
                RealtimeSpatialEvent.StableState.INSIDE,
                2, ingestSequence, 1, occurredAt,
                new RealtimeSpatialEvent.Evidence(
                        10, 20, null, "INSIDE", "GOOD", null,
                        "sha256:" + "a".repeat(64)));
    }

    private void makeIdle(UUID... eventIds) {
        for (UUID eventId : eventIds) {
            jdbcTemplate.update("""
                    UPDATE mine_operation_spatial_event_queue
                    SET received_at = CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                    WHERE event_id = ?
                    """, eventId);
        }
    }

    private List<Optional<OperationEventQueue.ClaimedEvent>> concurrentClaims(int workerCount) {
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<OperationEventQueue.ClaimedEvent>>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workerCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发认领启动栅栏超时");
                    }
                    return queue.claimNext(POLICY);
                }));
            }
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发认领线程未就绪");
            }
            start.countDown();
            List<Optional<OperationEventQueue.ClaimedEvent>> claims = new ArrayList<>();
            for (Future<Optional<OperationEventQueue.ClaimedEvent>> future : futures) {
                claims.add(future.get(10, TimeUnit.SECONDS));
            }
            return claims;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发认领被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("并发认领失败", exception);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static UUID uuidV7(int suffix) {
        return UUID.fromString("019f64e5-a000-7000-8000-" + "%012x".formatted(suffix));
    }
}
