package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.BindingPreviewResponse;
import com.bosco.mine.server.modules.mine.dto.BindingSwitchRequest;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.service.MineBindingService;
import com.bosco.mine.server.modules.mine.service.MineRequestIdentity;
import com.bosco.mine.server.modules.mine.service.MineScopeCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 使用真实 PostgreSQL 校验矿区资产换绑、幂等、资质和发生时解析闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(MineBindingServiceIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MineBindingServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private MineBindingService bindingService;

    @Autowired
    private MineScopeCommandService scopeCommandService;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID userId;
    private UUID organizationId;
    private UUID firstSiteId;
    private UUID secondSiteId;
    private UUID firstVehicleId;
    private UUID secondVehicleId;
    private UUID thirdVehicleId;
    private UUID otherSiteVehicleId;
    private UUID firstDeviceId;
    private UUID secondDeviceId;
    private UUID firstTagId;
    private UUID archivedTagId;
    private UUID personId;
    private Instant effectiveAt;

    @BeforeEach
    void 准备可换绑的矿区资产() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_location_runtime_snapshot_item,
                    mine_location_runtime_snapshot,
                    mine_binding_switch_operation,
                    mine_person_vehicle_binding,
                    mine_person_tag_binding,
                    mine_person_site_assignment,
                    mine_vehicle_tag_binding,
                    mine_location_tag_binding_claim,
                    mine_vehicle_device_binding,
                    mine_driver_qualification,
                    mine_person,
                    mine_location_tag,
                    mine_vehicle,
                    dev_device,
                    dev_product,
                    mine_user_scope_grant,
                    mine_site,
                    mine_organization,
                    sys_outbox_event,
                    sys_operation_log
                CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'mine-binding-it-%'");
        userId = createUser();
        identity.use(userId);
        MineOrganizationResponse organization = scopeCommandService.createOrganization(
                "binding-service-group", "换绑服务测试集团");
        organizationId = organization.id();
        firstSiteId = scopeCommandService.createSite(
                organizationId, "binding-first-pit", "换绑一矿", "Asia/Shanghai", null).id();
        secondSiteId = scopeCommandService.createSite(
                organizationId, "binding-second-pit", "换绑二矿", "Asia/Shanghai", null).id();

        UUID productId = insertProduct();
        firstDeviceId = insertDevice(productId, "binding-device-1");
        secondDeviceId = insertDevice(productId, "binding-device-2");
        firstVehicleId = insertVehicle(firstSiteId, "binding-vehicle-1", "ACTIVE");
        secondVehicleId = insertVehicle(firstSiteId, "binding-vehicle-2", "ACTIVE");
        thirdVehicleId = insertVehicle(firstSiteId, "binding-vehicle-3", "ACTIVE");
        otherSiteVehicleId = insertVehicle(secondSiteId, "binding-vehicle-other", "ACTIVE");
        firstTagId = insertTag(firstSiteId, "binding-tag-1", "ACTIVE");
        archivedTagId = insertTag(firstSiteId, "binding-tag-archived", "ARCHIVED");
        personId = insertPerson();
        effectiveAt = Instant.now().plusSeconds(600);
        insertPersonSiteAssignment(
                personId, effectiveAt.minusSeconds(86_400), effectiveAt.plusSeconds(86_400));
        insertQualification(
                personId, List.of("HAUL_TRUCK"),
                localDate(effectiveAt).minusDays(1), localDate(effectiveAt).plusDays(1));
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_location_runtime_snapshot_item,
                    mine_location_runtime_snapshot,
                    sys_outbox_event
                CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_operation_log");
    }

    @Test
    void 预览司机换车应返回旧关系目标关系和有效资质() {
        UUID oldBindingId = insertPersonVehicle(
                personId, firstVehicleId, "PRIMARY_DRIVER",
                effectiveAt.minusSeconds(3_600), effectiveAt.plusSeconds(86_400));

        BindingPreviewResponse preview = bindingService.preview(
                firstSiteId,
                request("PERSON_VEHICLE", personId, secondVehicleId,
                        "PRIMARY_DRIVER", effectiveAt));

        assertThat(preview.executable()).isTrue();
        assertThat(preview.executed()).isFalse();
        assertThat(preview.previousBinding()).isNotNull();
        assertThat(preview.previousBinding().bindingId()).isEqualTo(oldBindingId);
        assertThat(preview.previousBinding().targetId()).isEqualTo(firstVehicleId);
        assertThat(preview.proposedBinding().targetId()).isEqualTo(secondVehicleId);
        assertThat(preview.proposedBinding().validTo())
                .isCloseTo(effectiveAt.plusSeconds(86_400), within(1, java.time.temporal.ChronoUnit.MICROS));
        assertThat(preview.qualification().required()).isTrue();
        assertThat(preview.qualification().qualified()).isTrue();
        assertThat(preview.blockers()).isEmpty();
        assertThat(preview.affectedObjects())
                .extracting(BindingPreviewResponse.AffectedObject::objectType)
                .containsExactlyInAnyOrder("PERSON", "VEHICLE", "VEHICLE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_person_vehicle_binding", Long.class)).isOne();
    }

    @Test
    void 过去时间跨矿目标和归档资源必须被拒绝() {
        assertCode(
                () -> bindingService.preview(
                        firstSiteId,
                        request("VEHICLE_DEVICE", firstDeviceId, firstVehicleId,
                                "PRIMARY", Instant.now().minusSeconds(60))),
                "MINE-BINDING-422-001");
        assertCode(
                () -> bindingService.preview(
                        firstSiteId,
                        request("VEHICLE_DEVICE", firstDeviceId, firstVehicleId,
                                "PRIMARY", Instant.now().plusSeconds(367L * 86_400L))),
                "MINE-BINDING-422-001");
        assertCode(
                () -> bindingService.preview(
                        firstSiteId,
                        request("VEHICLE_DEVICE", firstDeviceId, otherSiteVehicleId,
                                "PRIMARY", effectiveAt)),
                "MINE-BINDING-404-001");
        assertCode(
                () -> bindingService.preview(
                        firstSiteId,
                        request("VEHICLE_TAG", archivedTagId, firstVehicleId,
                                null, effectiveAt)),
                "MINE-BINDING-404-002");
    }

    @Test
    void 司机资质车型或有效期不满足时预览和执行都必须拒绝() {
        jdbcTemplate.update("""
                UPDATE mine_driver_qualification
                SET allowed_vehicle_types = '["EXCAVATOR"]'::jsonb
                WHERE person_id = ?
                """, personId);
        BindingSwitchRequest command = request(
                "PERSON_VEHICLE", personId, secondVehicleId,
                "PRIMARY_DRIVER", effectiveAt);
        BindingPreviewResponse wrongType = bindingService.preview(firstSiteId, command);
        assertThat(wrongType.executable()).isFalse();
        assertThat(wrongType.qualification().qualified()).isFalse();
        assertThat(wrongType.blockers()).containsExactly("DRIVER_QUALIFICATION_INVALID");
        assertCode(
                () -> bindingService.switchBinding(firstSiteId, command, "qualification-type"),
                "MINE-BINDING-422-003");

        jdbcTemplate.update("""
                UPDATE mine_driver_qualification
                SET allowed_vehicle_types = '["HAUL_TRUCK"]'::jsonb,
                    valid_to = ?
                WHERE person_id = ?
                """, localDate(effectiveAt).minusDays(1), personId);
        assertThat(bindingService.preview(firstSiteId, command).qualification().qualified()).isFalse();
    }

    @Test
    void 司机关系上界必须取任职结束与资质到期次日零点的较早值() {
        LocalDate qualificationEnd = localDate(effectiveAt);
        jdbcTemplate.update("""
                UPDATE mine_driver_qualification
                SET valid_to = ?
                WHERE person_id = ?
                """, qualificationEnd, personId);
        Instant qualificationExclusiveEnd = qualificationEnd.plusDays(1)
                .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant();

        BindingPreviewResponse preview = bindingService.preview(
                firstSiteId,
                request("PERSON_VEHICLE", personId, secondVehicleId,
                        "PRIMARY_DRIVER", effectiveAt));
        BindingPreviewResponse switched = bindingService.switchBinding(
                firstSiteId,
                request("PERSON_VEHICLE", personId, secondVehicleId,
                        "PRIMARY_DRIVER", effectiveAt),
                "qualification-upper-bound");

        assertThat(preview.proposedBinding().validTo()).isEqualTo(qualificationExclusiveEnd);
        assertThat(switched.proposedBinding().validTo()).isEqualTo(qualificationExclusiveEnd);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT valid_to = ?
                FROM mine_person_vehicle_binding
                WHERE id = ?
                """, Boolean.class, databaseTime(qualificationExclusiveEnd),
                switched.proposedBinding().bindingId())).isTrue();
    }

    @Test
    void 预览必须识别四类关系的目标侧占用而不能误报可执行() {
        UUID secondTagId = insertTag(firstSiteId, "binding-tag-2", "ACTIVE");
        UUID secondPersonId = insertPerson("目标占用司机");
        insertPersonSiteAssignment(
                secondPersonId, effectiveAt.minusSeconds(86_400),
                effectiveAt.plusSeconds(86_400));
        insertVehicleDevice(
                thirdVehicleId, secondDeviceId, "PRIMARY",
                effectiveAt.minusSeconds(3_600), null);
        insertVehicleTag(
                firstVehicleId, firstTagId,
                effectiveAt.minusSeconds(3_600), null);
        insertPersonTag(
                personId, secondTagId,
                effectiveAt.minusSeconds(3_600), effectiveAt.plusSeconds(86_400));
        insertPersonVehicle(
                secondPersonId, secondVehicleId, "PRIMARY_DRIVER",
                effectiveAt.minusSeconds(3_600), effectiveAt.plusSeconds(86_400));

        assertTargetOccupied(request(
                "VEHICLE_DEVICE", firstDeviceId, thirdVehicleId,
                "PRIMARY", effectiveAt));
        assertTargetOccupied(request(
                "VEHICLE_TAG", secondTagId, firstVehicleId,
                null, effectiveAt));
        assertTargetOccupied(request(
                "PERSON_TAG", firstTagId, personId,
                null, effectiveAt));
        assertTargetOccupied(request(
                "PERSON_VEHICLE", personId, secondVehicleId,
                "PRIMARY_DRIVER", effectiveAt));
    }

    @Test
    void 预览必须识别拟建区间内四类关系的未来目标占用() {
        Instant reservationAt = effectiveAt.plusSeconds(3_600);
        UUID secondTagId = insertTag(firstSiteId, "future-target-tag-2", "ACTIVE");
        UUID thirdTagId = insertTag(firstSiteId, "future-target-tag-3", "ACTIVE");
        UUID fourthTagId = insertTag(firstSiteId, "future-target-tag-4", "ACTIVE");
        UUID targetPersonId = insertPerson("未来标签目标人员");
        UUID reservedDriverId = insertPerson("未来主司机");
        insertPersonSiteAssignment(
                targetPersonId, effectiveAt.minusSeconds(86_400),
                effectiveAt.plusSeconds(86_400));
        insertPersonSiteAssignment(
                reservedDriverId, effectiveAt.minusSeconds(86_400),
                effectiveAt.plusSeconds(86_400));

        insertVehicleDevice(firstVehicleId, secondDeviceId, "PRIMARY", reservationAt, null);
        insertVehicleTag(secondVehicleId, secondTagId, reservationAt, null);
        insertPersonTag(
                targetPersonId, thirdTagId, reservationAt, effectiveAt.plusSeconds(86_400));
        insertPersonVehicle(
                reservedDriverId, thirdVehicleId, "PRIMARY_DRIVER",
                reservationAt, effectiveAt.plusSeconds(86_400));

        assertTargetOccupied(request(
                "VEHICLE_DEVICE", firstDeviceId, firstVehicleId,
                "PRIMARY", effectiveAt));
        assertTargetOccupied(request(
                "VEHICLE_TAG", fourthTagId, secondVehicleId,
                null, effectiveAt));
        assertTargetOccupied(request(
                "PERSON_TAG", firstTagId, targetPersonId,
                null, effectiveAt));
        assertTargetOccupied(request(
                "PERSON_VEHICLE", personId, thirdVehicleId,
                "PRIMARY_DRIVER", effectiveAt));
    }

    @Test
    void 预览必须识别拟建区间内资源已有的未来预约() {
        Instant reservationAt = effectiveAt.plusSeconds(3_600);
        UUID vehicleTagId = insertTag(firstSiteId, "future-resource-vehicle-tag", "ACTIVE");
        UUID personTagId = insertTag(firstSiteId, "future-resource-person-tag", "ACTIVE");
        UUID targetPersonId = insertPerson("未来资源标签目标人员");
        UUID futureDriverId = insertPerson("未来资源司机");
        insertPersonSiteAssignment(
                targetPersonId, effectiveAt.minusSeconds(86_400),
                effectiveAt.plusSeconds(86_400));
        insertPersonSiteAssignment(
                futureDriverId, effectiveAt.minusSeconds(86_400),
                effectiveAt.plusSeconds(86_400));
        insertQualification(
                futureDriverId, List.of("HAUL_TRUCK"),
                localDate(effectiveAt).minusDays(1), localDate(effectiveAt).plusDays(1));

        insertVehicleDevice(firstVehicleId, firstDeviceId, "PRIMARY", reservationAt, null);
        insertVehicleTag(firstVehicleId, vehicleTagId, reservationAt, null);
        insertPersonTag(
                targetPersonId, personTagId, reservationAt, effectiveAt.plusSeconds(86_400));
        insertPersonVehicle(
                futureDriverId, firstVehicleId, "PRIMARY_DRIVER",
                reservationAt, effectiveAt.plusSeconds(86_400));

        assertResourceOccupied(request(
                "VEHICLE_DEVICE", firstDeviceId, secondVehicleId,
                "PRIMARY", effectiveAt));
        assertResourceOccupied(request(
                "VEHICLE_TAG", vehicleTagId, secondVehicleId,
                null, effectiveAt));
        assertResourceOccupied(request(
                "PERSON_TAG", personTagId, personId,
                null, effectiveAt));
        assertResourceOccupied(request(
                "PERSON_VEHICLE", futureDriverId, secondVehicleId,
                "PRIMARY_DRIVER", effectiveAt));
    }

    @Test
    void 标签从车辆换到人员后必须按发生时间解析旧新归属() {
        UUID oldBindingId = insertVehicleTag(
                firstVehicleId, firstTagId, effectiveAt.minusSeconds(3_600), null);
        BindingPreviewResponse switched = bindingService.switchBinding(
                firstSiteId,
                request("PERSON_TAG", firstTagId, personId, null, effectiveAt),
                "switch-tag-owner");

        assertThat(switched.executed()).isTrue();
        assertThat(switched.operationId()).isNotNull();
        assertThat(switched.previousBinding().bindingId()).isEqualTo(oldBindingId);
        assertThat(switched.proposedBinding().bindingId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT valid_to = ? FROM mine_vehicle_tag_binding WHERE id = ?
                """, Boolean.class, databaseTime(effectiveAt), oldBindingId)).isTrue();
        assertThat(bindingService.resolveTag(firstTagId, effectiveAt.minusSeconds(1)))
                .get()
                .satisfies(resolved -> {
                    assertThat(resolved.bindingType()).isEqualTo("VEHICLE_TAG");
                    assertThat(resolved.targetId()).isEqualTo(firstVehicleId);
                    assertThat(resolved.bindingId()).isEqualTo(oldBindingId);
                });
        assertThat(bindingService.resolveTag(firstTagId, effectiveAt))
                .get()
                .satisfies(resolved -> {
                    assertThat(resolved.bindingType()).isEqualTo("PERSON_TAG");
                    assertThat(resolved.targetId()).isEqualTo(personId);
                    assertThat(resolved.bindingId())
                            .isEqualTo(switched.proposedBinding().bindingId());
                });
        String snapshotContent = jdbcTemplate.queryForObject("""
                SELECT string_agg(item.content::TEXT, '')
                FROM mine_location_runtime_snapshot_item item
                JOIN mine_location_runtime_snapshot snapshot ON snapshot.id = item.snapshot_id
                WHERE snapshot.site_id = ?
                  AND snapshot.revision = (
                      SELECT MAX(latest.revision)
                      FROM mine_location_runtime_snapshot latest
                      WHERE latest.site_id = ?
                  )
                """, String.class, firstSiteId, firstSiteId);
        assertThat(snapshotContent)
                .contains(personId.toString())
                .doesNotContain(
                        "换绑测试司机", "138****8000", "410***********1234",
                        "phone", "certificate", "ciphertext");
    }

    @Test
    void 四类关系执行后设备标签和司机都必须按发生时间解析() {
        UUID oldDeviceBindingId = insertVehicleDevice(
                firstVehicleId, firstDeviceId, "PRIMARY",
                effectiveAt.minusSeconds(3_600), null);
        UUID oldPersonVehicleBindingId = insertPersonVehicle(
                personId, firstVehicleId, "PRIMARY_DRIVER",
                effectiveAt.minusSeconds(3_600), effectiveAt.plusSeconds(86_400));

        BindingPreviewResponse deviceSwitch = bindingService.switchBinding(
                firstSiteId,
                request("VEHICLE_DEVICE", firstDeviceId, secondVehicleId,
                        "PRIMARY", effectiveAt),
                "resolve-device-history");
        BindingPreviewResponse tagSwitch = bindingService.switchBinding(
                firstSiteId,
                request("VEHICLE_TAG", firstTagId, thirdVehicleId,
                        null, effectiveAt),
                "create-vehicle-tag");
        BindingPreviewResponse personSwitch = bindingService.switchBinding(
                firstSiteId,
                request("PERSON_VEHICLE", personId, secondVehicleId,
                        "PRIMARY_DRIVER", effectiveAt),
                "resolve-driver-history");

        assertThat(deviceSwitch.previousBinding().bindingId()).isEqualTo(oldDeviceBindingId);
        assertThat(bindingService.resolveDevice(firstDeviceId, effectiveAt.minusSeconds(1)))
                .get()
                .extracting(MineBindingService.BindingResolution::targetId)
                .isEqualTo(firstVehicleId);
        assertThat(bindingService.resolveDevice(firstDeviceId, effectiveAt))
                .get()
                .extracting(MineBindingService.BindingResolution::targetId)
                .isEqualTo(secondVehicleId);

        assertThat(tagSwitch.previousBinding()).isNull();
        assertThat(bindingService.resolveTag(firstTagId, effectiveAt))
                .get()
                .satisfies(resolved -> {
                    assertThat(resolved.bindingType()).isEqualTo("VEHICLE_TAG");
                    assertThat(resolved.targetId()).isEqualTo(thirdVehicleId);
                });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM mine_location_tag_binding_claim
                WHERE binding_id = ? AND binding_type = 'VEHICLE'
                """, Long.class, tagSwitch.proposedBinding().bindingId())).isOne();

        assertThat(personSwitch.previousBinding().bindingId())
                .isEqualTo(oldPersonVehicleBindingId);
        assertThat(bindingService.resolvePersonVehicle(personId, effectiveAt.minusSeconds(1)))
                .get()
                .extracting(MineBindingService.BindingResolution::targetId)
                .isEqualTo(firstVehicleId);
        assertThat(bindingService.resolvePersonVehicle(personId, effectiveAt))
                .get()
                .extracting(MineBindingService.BindingResolution::targetId)
                .isEqualTo(secondVehicleId);
    }

    @Test
    void 相同幂等键只能执行一次且异请求必须稳定冲突() {
        UUID oldBindingId = insertVehicleDevice(
                firstVehicleId, firstDeviceId, "PRIMARY",
                effectiveAt.minusSeconds(3_600), null);
        BindingSwitchRequest command = request(
                "VEHICLE_DEVICE", firstDeviceId, secondVehicleId,
                "PRIMARY", effectiveAt);

        BindingPreviewResponse first = bindingService.switchBinding(
                firstSiteId, command, "switch-device-once");
        BindingPreviewResponse repeated = bindingService.switchBinding(
                firstSiteId, command, "switch-device-once");

        assertThat(repeated.operationId()).isEqualTo(first.operationId());
        assertThat(repeated.proposedBinding().bindingId())
                .isEqualTo(first.proposedBinding().bindingId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_binding_switch_operation", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_vehicle_device_binding", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_outbox_event
                WHERE event_type = 'location.runtime.changed'
                """, Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                WHERE action = 'SWITCH_BINDING'
                """, Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT valid_to = ? FROM mine_vehicle_device_binding WHERE id = ?",
                Boolean.class, databaseTime(effectiveAt), oldBindingId)).isTrue();

        assertCode(
                () -> bindingService.switchBinding(
                        firstSiteId,
                        request("VEHICLE_DEVICE", firstDeviceId, thirdVehicleId,
                                "PRIMARY", effectiveAt),
                        "switch-device-once"),
                "MINE-BINDING-409-001");
    }

    @Test
    void 首次关系后来再次换绑后重放仍必须返回第一次不可变响应() {
        BindingSwitchRequest firstCommand = request(
                "VEHICLE_DEVICE", firstDeviceId, firstVehicleId,
                "PRIMARY", effectiveAt);
        BindingPreviewResponse first = bindingService.switchBinding(
                firstSiteId, firstCommand, "immutable-first-response");
        Instant secondEffectiveAt = effectiveAt.plusSeconds(300);
        bindingService.switchBinding(
                firstSiteId,
                request("VEHICLE_DEVICE", firstDeviceId, secondVehicleId,
                        "PRIMARY", secondEffectiveAt),
                "move-device-again");

        BindingPreviewResponse replayed = bindingService.switchBinding(
                firstSiteId, firstCommand, "immutable-first-response");

        assertThat(first.proposedBinding().validTo()).isNull();
        assertThat(replayed).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT valid_to = ?
                FROM mine_vehicle_device_binding
                WHERE id = ?
                """, Boolean.class, databaseTime(secondEffectiveAt),
                first.proposedBinding().bindingId())).isTrue();
    }

    @Test
    void 并发争用同一主关系时失败方必须返回稳定冲突并完整回滚() throws Exception {
        BindingSwitchRequest firstCommand = request(
                "VEHICLE_DEVICE", firstDeviceId, thirdVehicleId,
                "PRIMARY", effectiveAt);
        BindingSwitchRequest secondCommand = request(
                "VEHICLE_DEVICE", secondDeviceId, thirdVehicleId,
                "PRIMARY", effectiveAt);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> concurrentSwitch(
                    firstCommand, "primary-device-a", ready, start));
            Future<Object> second = executor.submit(() -> concurrentSwitch(
                    secondCommand, "primary-device-b", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(BindingPreviewResponse.class::isInstance).count())
                    .isOne();
            assertThat(results.stream().filter(MineException.class::isInstance)
                    .map(MineException.class::cast)
                    .map(exception -> exception.errorCode().code()))
                    .containsExactly("MINE-BINDING-409-002");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_binding_switch_operation", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_outbox_event WHERE event_type = 'location.runtime.changed'",
                Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE action = 'SWITCH_BINDING'",
                Long.class)).isOne();
    }

    @Test
    void 任职收缩与司机换绑必须共用人员时间轴锁并在获锁后重新校验() throws Exception {
        BindingSwitchRequest command = request(
                "PERSON_VEHICLE", personId, secondVehicleId,
                "PRIMARY_DRIVER", effectiveAt);
        CountDownLatch assignmentUpdated = new CountDownLatch(1);
        CountDownLatch allowAssignmentCommit = new CountDownLatch(1);
        CountDownLatch switchStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> assignmentChange = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.query("""
                                SELECT pg_advisory_xact_lock(
                                    hashtextextended(CAST(? AS TEXT), 487311842019)
                                )
                                """, resultSet -> { }, personId);
                        jdbcTemplate.update("""
                                UPDATE mine_person_site_assignment
                                SET valid_to = ?
                                WHERE person_id = ?
                                """, databaseTime(effectiveAt.minusSeconds(1)), personId);
                        assignmentUpdated.countDown();
                        awaitLatch(allowAssignmentCommit);
                    }));
            assertThat(assignmentUpdated.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> switchResult = executor.submit(() -> {
                switchStarted.countDown();
                try {
                    return bindingService.switchBinding(
                            firstSiteId, command, "assignment-race");
                } catch (MineException exception) {
                    return exception;
                }
            });
            assertThat(switchStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> switchResult.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowAssignmentCommit.countDown();
            assignmentChange.get(10, TimeUnit.SECONDS);
            assertThat(switchResult.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(MineException.class, exception ->
                            assertThat(exception.errorCode().code())
                                    .isEqualTo("MINE-BINDING-404-002"));
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_person_vehicle_binding", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_binding_switch_operation", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_outbox_event WHERE event_type = 'location.runtime.changed'",
                Long.class)).isZero();
    }

    @Test
    void 等待资源锁期间生效时刻变成过去后必须重新拒绝历史改写() throws Exception {
        Instant nearFuture = Instant.now().plusMillis(350);
        CountDownLatch resourceLocked = new CountDownLatch(1);
        CountDownLatch allowRelease = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> lockOwner = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.query("""
                                SELECT pg_advisory_xact_lock(
                                    hashtextextended(CAST(? AS TEXT), 487311842021)
                                )
                                """, resultSet -> { }, firstDeviceId);
                        resourceLocked.countDown();
                        awaitLatch(allowRelease);
                    }));
            assertThat(resourceLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> switchResult = executor.submit(() -> {
                try {
                    return bindingService.switchBinding(
                            firstSiteId,
                            request("VEHICLE_DEVICE", firstDeviceId, firstVehicleId,
                                    "PRIMARY", nearFuture),
                            "effective-time-after-lock");
                } catch (MineException exception) {
                    return exception;
                }
            });
            assertThatThrownBy(() -> switchResult.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowRelease.countDown();
            lockOwner.get(10, TimeUnit.SECONDS);
            assertThat(switchResult.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(MineException.class, exception ->
                            assertThat(exception.errorCode().code())
                                    .isEqualTo("MINE-BINDING-422-001"));
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_vehicle_device_binding", Long.class)).isZero();
    }

    @Test
    void 执行必须等待资产状态锁并在归档禁用或车型变化后重新校验() throws Exception {
        Object vehicleArchived = mutateAssetWhileSwitchWaits(
                () -> jdbcTemplate.update("""
                        UPDATE mine_vehicle
                        SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, secondVehicleId),
                request("VEHICLE_DEVICE", firstDeviceId, secondVehicleId,
                        "PRIMARY", effectiveAt),
                "vehicle-archived-during-switch");
        assertExceptionCode(vehicleArchived, "MINE-BINDING-404-001");

        Object tagArchived = mutateAssetWhileSwitchWaits(
                () -> jdbcTemplate.update("""
                        UPDATE mine_location_tag
                        SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, firstTagId),
                request("VEHICLE_TAG", firstTagId, firstVehicleId,
                        null, effectiveAt),
                "tag-archived-during-switch");
        assertExceptionCode(tagArchived, "MINE-BINDING-404-002");

        Object deviceDisabled = mutateAssetWhileSwitchWaits(
                () -> jdbcTemplate.update("""
                        UPDATE dev_device SET status = 'DISABLED' WHERE id = ?
                        """, firstDeviceId),
                request("VEHICLE_DEVICE", firstDeviceId, firstVehicleId,
                        "PRIMARY", effectiveAt),
                "device-disabled-during-switch");
        assertExceptionCode(deviceDisabled, "MINE-BINDING-404-002");

        Object vehicleTypeChanged = mutateAssetWhileSwitchWaits(
                () -> jdbcTemplate.update("""
                        UPDATE mine_vehicle SET vehicle_type = 'EXCAVATOR' WHERE id = ?
                        """, thirdVehicleId),
                request("PERSON_VEHICLE", personId, thirdVehicleId,
                        "PRIMARY_DRIVER", effectiveAt),
                "vehicle-type-changed-during-switch");
        assertExceptionCode(vehicleTypeChanged, "MINE-BINDING-422-003");
    }

    @Test
    void 人员标签交叉换绑必须按人员标识固定锁序且只返回稳定占用冲突() throws Exception {
        UUID secondTagId = insertTag(firstSiteId, "binding-tag-cross", "ACTIVE");
        UUID secondPersonId = insertPerson("交叉换绑司机");
        insertPersonSiteAssignment(
                secondPersonId, effectiveAt.minusSeconds(86_400),
                effectiveAt.plusSeconds(86_400));
        insertPersonTag(
                personId, firstTagId,
                effectiveAt.minusSeconds(3_600), effectiveAt.plusSeconds(86_400));
        insertPersonTag(
                secondPersonId, secondTagId,
                effectiveAt.minusSeconds(3_600), effectiveAt.plusSeconds(86_400));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> concurrentSwitch(
                    request("PERSON_TAG", firstTagId, secondPersonId, null, effectiveAt),
                    "cross-person-a", ready, start));
            Future<Object> second = executor.submit(() -> concurrentSwitch(
                    request("PERSON_TAG", secondTagId, personId, null, effectiveAt),
                    "cross-person-b", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertExceptionCode(first.get(10, TimeUnit.SECONDS), "MINE-BINDING-409-002");
            assertExceptionCode(second.get(10, TimeUnit.SECONDS), "MINE-BINDING-409-002");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_binding_switch_operation", Long.class)).isZero();
    }

    private Object concurrentSwitch(
            BindingSwitchRequest request,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            return bindingService.switchBinding(firstSiteId, request, idempotencyKey);
        } catch (MineException exception) {
            return exception;
        }
    }

    private Object mutateAssetWhileSwitchWaits(
            Runnable mutation,
            BindingSwitchRequest request,
            String idempotencyKey
    ) throws Exception {
        CountDownLatch assetChanged = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> mutationResult = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        mutation.run();
                        assetChanged.countDown();
                        awaitLatch(allowCommit);
                    }));
            assertThat(assetChanged.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> switchResult = executor.submit(() -> {
                try {
                    return bindingService.switchBinding(firstSiteId, request, idempotencyKey);
                } catch (MineException exception) {
                    return exception;
                }
            });
            try {
                assertThatThrownBy(() -> switchResult.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                allowCommit.countDown();
            }
            mutationResult.get(10, TimeUnit.SECONDS);
            return switchResult.get(10, TimeUnit.SECONDS);
        }
    }

    private BindingSwitchRequest request(
            String bindingType,
            UUID resourceId,
            UUID targetId,
            String role,
            Instant at
    ) {
        return new BindingSwitchRequest(
                bindingType, resourceId, targetId, role, at, "测试换绑原因");
    }

    private void assertCode(Runnable invocation, String expectedCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo(expectedCode));
    }

    private void assertTargetOccupied(BindingSwitchRequest request) {
        BindingPreviewResponse preview = bindingService.preview(firstSiteId, request);
        assertThat(preview.executable()).isFalse();
        assertThat(preview.blockers()).contains("TARGET_ROLE_OCCUPIED");
    }

    private void assertResourceOccupied(BindingSwitchRequest request) {
        BindingPreviewResponse preview = bindingService.preview(firstSiteId, request);
        assertThat(preview.executable()).isFalse();
        assertThat(preview.blockers()).contains("BINDING_START_CONFLICT");
    }

    private void assertExceptionCode(Object result, String expectedCode) {
        assertThat(result).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo(expectedCode));
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试闩锁超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发测试闩锁被中断", exception);
        }
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', '换绑集成测试用户')
                """, id, "mine-binding-it-" + id);
        return id;
    }

    private UUID insertProduct() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dev_product (id, product_key, name, device_type, status)
                VALUES (?, ?, '换绑定位产品', 'LOCATION', 'ACTIVE')
                """, id, "binding-product-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID insertDevice(UUID productId, String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dev_device (id, product_id, device_code, name, status)
                VALUES (?, ?, ?, ?, 'ENABLED')
                """, id, productId, code + "-" + id, code);
        return id;
    }

    private UUID insertVehicle(UUID siteId, String code, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type,
                    status, archived_at
                ) VALUES (
                    ?, ?, ?, ?, ?, 'HAUL_TRUCK', ?,
                    CASE WHEN ? = 'ARCHIVED' THEN CURRENT_TIMESTAMP ELSE NULL END
                )
                """, id, organizationId, siteId, code + "-" + id, code, status, status);
        return id;
    }

    private UUID insertTag(UUID siteId, String code, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_location_tag (
                    id, organization_id, site_id, tag_code, status, archived_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'ARCHIVED' THEN CURRENT_TIMESTAMP ELSE NULL END
                )
                """, id, organizationId, siteId, code + "-" + id, status, status);
        return id;
    }

    private UUID insertPerson() {
        return insertPerson("换绑测试司机");
    }

    private UUID insertPerson(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_person (
                    id, organization_id, person_code, name, person_type,
                    phone_ciphertext, phone_masked, certificate_ciphertext,
                    certificate_masked, status
                ) VALUES (
                    ?, ?, ?, ?, 'DRIVER',
                    'v2:bm9uY2U:Y2lwaGVydGV4dA', '138****8000',
                    'v2:bm9uY2U:Y2lwaGVydGV4dA', '410***********1234', 'ACTIVE'
                )
                """, id, organizationId, "binding-person-" + id, name);
        return id;
    }

    private void insertPersonSiteAssignment(UUID targetPersonId, Instant from, Instant to) {
        jdbcTemplate.update("""
                INSERT INTO mine_person_site_assignment (
                    id, organization_id, site_id, person_id, valid_from, valid_to, reason
                ) VALUES (?, ?, ?, ?, ?, ?, 'TEST_ASSIGNMENT')
                """, UUID.randomUUID(), organizationId, firstSiteId, targetPersonId,
                databaseTime(from), databaseTime(to));
    }

    private void insertQualification(
            UUID targetPersonId,
            List<String> vehicleTypes,
            LocalDate from,
            LocalDate to
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_driver_qualification (
                    id, organization_id, person_id, qualification_code, name,
                    allowed_vehicle_types, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, '换绑测试资质', CAST(? AS JSONB), ?, ?)
                """, UUID.randomUUID(), organizationId, targetPersonId,
                "binding-license-" + UUID.randomUUID(),
                "[\"" + String.join("\",\"", vehicleTypes) + "\"]", from, to);
    }

    private UUID insertVehicleDevice(
            UUID vehicleId,
            UUID deviceId,
            String role,
            Instant from,
            Instant to
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle_device_binding (
                    id, organization_id, site_id, vehicle_id, device_id,
                    binding_role, valid_from, valid_to, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'TEST_BINDING')
                """, id, organizationId, firstSiteId, vehicleId, deviceId, role,
                databaseTime(from), databaseTime(to));
        return id;
    }

    private UUID insertVehicleTag(UUID vehicleId, UUID tagId, Instant from, Instant to) {
        UUID id = UUID.randomUUID();
        inTransaction(() -> {
            jdbcTemplate.update("""
                    INSERT INTO mine_location_tag_binding_claim (
                        binding_id, organization_id, site_id, binding_type,
                        tag_id, valid_from, valid_to
                    ) VALUES (?, ?, ?, 'VEHICLE', ?, ?, ?)
                    """, id, organizationId, firstSiteId, tagId,
                    databaseTime(from), databaseTime(to));
            jdbcTemplate.update("""
                    INSERT INTO mine_vehicle_tag_binding (
                        id, organization_id, site_id, vehicle_id, tag_id,
                        valid_from, valid_to, reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST_BINDING')
                    """, id, organizationId, firstSiteId, vehicleId, tagId,
                    databaseTime(from), databaseTime(to));
        });
        return id;
    }

    private UUID insertPersonTag(UUID targetPersonId, UUID tagId, Instant from, Instant to) {
        UUID id = UUID.randomUUID();
        inTransaction(() -> {
            jdbcTemplate.update("""
                    INSERT INTO mine_location_tag_binding_claim (
                        binding_id, organization_id, site_id, binding_type,
                        tag_id, valid_from, valid_to
                    ) VALUES (?, ?, ?, 'PERSON', ?, ?, ?)
                    """, id, organizationId, firstSiteId, tagId,
                    databaseTime(from), databaseTime(to));
            jdbcTemplate.update("""
                    INSERT INTO mine_person_tag_binding (
                        id, organization_id, site_id, person_id, tag_id,
                        valid_from, valid_to, reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST_BINDING')
                    """, id, organizationId, firstSiteId, targetPersonId, tagId,
                    databaseTime(from), databaseTime(to));
        });
        return id;
    }

    private UUID insertPersonVehicle(
            UUID targetPersonId,
            UUID vehicleId,
            String role,
            Instant from,
            Instant to
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_person_vehicle_binding (
                    id, organization_id, site_id, person_id, vehicle_id,
                    assignment_role, valid_from, valid_to, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'TEST_BINDING')
                """, id, organizationId, firstSiteId, targetPersonId, vehicleId, role,
                databaseTime(from), databaseTime(to));
        return id;
    }

    private LocalDate localDate(Instant instant) {
        return instant.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    private OffsetDateTime databaseTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    /** 为集成测试提供不依赖 Redis 会话的稳定请求身份。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityTestConfiguration {

        /** 返回可切换的测试身份。 */
        @Bean
        @Primary
        MutableMineRequestIdentity mutableMineRequestIdentity() {
            return new MutableMineRequestIdentity();
        }
    }

    /** 集成测试使用的可切换身份。 */
    static final class MutableMineRequestIdentity implements MineRequestIdentity {
        private volatile UUID userId;

        /** 切换当前测试用户。 */
        void use(UUID value) {
            userId = value;
        }

        /** 返回当前测试用户。 */
        @Override
        public UUID currentUserId() {
            return userId;
        }
    }
}
