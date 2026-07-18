package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.controller.MineAssetController;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemResponse;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformResponse;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.LocationTagRequest;
import com.bosco.mine.server.modules.mine.dto.LocationTagResponse;
import com.bosco.mine.server.modules.mine.dto.LocationTagUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.dto.MineSiteResponse;
import com.bosco.mine.server.modules.mine.dto.VehicleRequest;
import com.bosco.mine.server.modules.mine.dto.VehicleResponse;
import com.bosco.mine.server.modules.mine.dto.VehicleUpdateRequest;
import com.bosco.mine.server.modules.mine.service.MineAssetCommandService;
import com.bosco.mine.server.modules.mine.service.MineAssetQueryService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验矿区车辆、标签和双坐标管理闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(MineAssetManagementIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MineAssetManagementIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private MineAssetCommandService commandService;

    @Autowired
    private MineAssetQueryService queryService;

    @Autowired
    private MineScopeCommandService scopeCommandService;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID firstUserId;
    private UUID secondUserId;
    private UUID firstSiteId;
    private UUID secondSiteId;

    @BeforeEach
    void 准备两个隔离矿区() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_coordinate_transform_version,
                    mine_coordinate_system,
                    mine_location_tag,
                    mine_vehicle,
                    mine_user_scope_grant,
                    mine_site,
                    mine_organization,
                    sys_operation_log
                CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'mine-asset-it-%'");
        firstUserId = createUser("first");
        secondUserId = createUser("second");

        identity.use(firstUserId);
        MineOrganizationResponse firstOrganization = scopeCommandService.createOrganization(
                "asset-north", "资产北方集团");
        firstSiteId = scopeCommandService.createSite(
                firstOrganization.id(), "north-pit", "北方矿区", "Asia/Shanghai", null).id();

        identity.use(secondUserId);
        MineOrganizationResponse secondOrganization = scopeCommandService.createOrganization(
                "asset-south", "资产南方集团");
        secondSiteId = scopeCommandService.createSite(
                secondOrganization.id(), "south-pit", "南方矿区", "Asia/Shanghai", null).id();
        identity.use(firstUserId);
    }

    @Test
    void 车辆和标签必须按矿区隔离分页并使用乐观锁归档() {
        VehicleResponse firstVehicle = commandService.createVehicle(
                firstSiteId,
                new VehicleRequest(
                        "truck-001", "北矿一号车", "HAUL_TRUCK", "N-001", null,
                        "矿卡品牌", "M90", new BigDecimal("90.500")));
        commandService.createVehicle(
                firstSiteId,
                new VehicleRequest(
                        "truck-002", "北矿二号车", "HAUL_TRUCK", "N-002", null,
                        "矿卡品牌", "M95", new BigDecimal("95.000")));
        LocationTagResponse tag = commandService.createLocationTag(
                firstSiteId,
                new LocationTagRequest(
                        "tag-001", "模拟厂商", "SIM-UWB", 88,
                        Instant.parse("2026-07-18T00:00:00Z")));

        PageResponse<VehicleResponse> page = queryService.findVehicles(
                firstSiteId, "北矿", "ACTIVE", 1, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(2);
        assertThatThrownBy(() -> queryService.findVehicles(
                firstSiteId, null, null, 1, 101))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-ASSET-422-001"));

        identity.use(secondUserId);
        assertThatThrownBy(() -> queryService.findVehicles(
                firstSiteId, null, null, 1, 20))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-403-002"));

        identity.use(firstUserId);
        VehicleResponse updated = commandService.updateVehicle(
                firstSiteId,
                firstVehicle.id(),
                new VehicleUpdateRequest(
                        "北矿主力一号车", "HAUL_TRUCK", "N-001", null,
                        "矿卡品牌", "M90-Pro", new BigDecimal("92.000"), firstVehicle.version()));
        assertThatThrownBy(() -> commandService.updateVehicle(
                firstSiteId,
                firstVehicle.id(),
                new VehicleUpdateRequest(
                        "并发覆盖", "HAUL_TRUCK", "N-001", null,
                        null, null, null, firstVehicle.version())))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-ASSET-409-002"));
        commandService.archiveVehicle(firstSiteId, firstVehicle.id(), updated.version());
        commandService.archiveLocationTag(firstSiteId, tag.id(), tag.version());

        assertThat(queryService.findVehicles(firstSiteId, null, "ARCHIVED", 1, 20).items())
                .extracting(VehicleResponse::id)
                .containsExactly(firstVehicle.id());
        assertThat(queryService.findVehicles(
                firstSiteId, null, null, Integer.MAX_VALUE, 100).items()).isEmpty();
        assertThat(queryService.findLocationTags(firstSiteId, null, "ARCHIVED", 1, 20).items())
                .extracting(LocationTagResponse::id)
                .containsExactly(tag.id());
        assertThat(auditActions()).contains(
                "CREATE_VEHICLE", "UPDATE_VEHICLE", "ARCHIVE_VEHICLE",
                "CREATE_LOCATION_TAG", "ARCHIVE_LOCATION_TAG");
    }

    @Test
    void 坐标转换必须拒绝跨矿引用并在发布后锁定版本和坐标语义() throws Exception {
        CoordinateSystemResponse source = commandService.createCoordinateSystem(
                firstSiteId,
                new CoordinateSystemRequest(
                        "vendor-local", "厂商本地坐标", "LOCAL_CARTESIAN", "METER",
                        null, false, -1_000D, 1_000D, -1_000D, 1_000D, null, null));
        CoordinateSystemResponse target = commandService.createCoordinateSystem(
                firstSiteId,
                new CoordinateSystemRequest(
                        "mine-local", "矿区统一坐标", "LOCAL_CARTESIAN", "METER",
                        null, true, 0D, 10_000D, 0D, 8_000D, -500D, 2_000D));

        identity.use(secondUserId);
        CoordinateSystemResponse foreignTarget = commandService.createCoordinateSystem(
                secondSiteId,
                new CoordinateSystemRequest(
                        "south-local", "南矿统一坐标", "LOCAL_CARTESIAN", "METER",
                        null, true, 0D, 5_000D, 0D, 4_000D, null, null));
        identity.use(firstUserId);

        assertThatThrownBy(() -> commandService.createCoordinateTransform(
                firstSiteId,
                new CoordinateTransformRequest(
                        source.id(), foreignTarget.id(), 1, "IDENTITY",
                        objectMapper.readTree("{}"), Instant.parse("2026-01-01T00:00:00Z"), null)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-ASSET-422-003"));

        assertThatThrownBy(() -> commandService.createCoordinateTransform(
                firstSiteId,
                new CoordinateTransformRequest(
                        source.id(), target.id(), 2, "WGS84_ENU",
                        objectMapper.readTree("""
                                {"originLongitude":116.3,"originLatitude":39.9,"originAltitude":80}
                                """), Instant.parse("2026-01-01T00:00:00Z"), null)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-ASSET-422-004"));

        CoordinateTransformResponse draft = commandService.createCoordinateTransform(
                firstSiteId,
                new CoordinateTransformRequest(
                        source.id(), target.id(), 1, "IDENTITY",
                        objectMapper.readTree("{}"), Instant.parse("2026-01-01T00:00:00Z"), null));
        assertThatThrownBy(() -> commandService.updateCoordinateTransform(
                firstSiteId,
                draft.id(),
                new CoordinateTransformUpdateRequest(
                        "WGS84_ENU",
                        objectMapper.readTree("""
                                {"originLongitude":116.3,"originLatitude":39.9,"originAltitude":80}
                                """),
                        Instant.parse("2026-01-01T00:00:00Z"), null, draft.version())))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-ASSET-422-004"));

        CoordinateTransformResponse tamperedDraft = commandService.createCoordinateTransform(
                firstSiteId,
                new CoordinateTransformRequest(
                        source.id(), target.id(), 3, "IDENTITY",
                        objectMapper.readTree("{}"), Instant.parse("2026-01-01T00:00:00Z"), null));
        jdbcTemplate.update("""
                UPDATE mine_coordinate_transform_version
                SET algorithm = 'WGS84_ENU',
                    parameters = '{"originLongitude":116.3,"originLatitude":39.9,
                                   "originAltitude":80}'::JSONB
                WHERE id = ?
                """, tamperedDraft.id());
        assertThatThrownBy(() -> commandService.publishCoordinateTransform(
                firstSiteId, tamperedDraft.id(), tamperedDraft.version()))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-ASSET-422-004"));

        CoordinateTransformResponse updated = commandService.updateCoordinateTransform(
                firstSiteId,
                draft.id(),
                new CoordinateTransformUpdateRequest(
                        "AFFINE_3D",
                        objectMapper.readTree(
                                "{\"matrix\":[1,0,0,0,0,1,0,0,0,0,1,0,10,20,0,1]}"),
                        Instant.parse("2026-01-01T00:00:00Z"), null, draft.version()));
        CoordinateTransformResponse published = commandService.publishCoordinateTransform(
                firstSiteId, draft.id(), updated.version());
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.contentHash()).startsWith("sha256:").hasSize(71);

        assertThatThrownBy(() -> commandService.updateCoordinateTransform(
                firstSiteId,
                draft.id(),
                new CoordinateTransformUpdateRequest(
                        "IDENTITY", objectMapper.readTree("{}"),
                        Instant.parse("2026-01-01T00:00:00Z"), null, published.version())))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-ASSET-409-006"));
        assertThatThrownBy(() -> commandService.updateCoordinateSystem(
                firstSiteId,
                source.id(),
                new CoordinateSystemUpdateRequest(
                        "被篡改的坐标", "LOCAL_CARTESIAN", "METER", null, false,
                        -1_000D, 2_000D, -1_000D, 1_000D, null, null, source.version())))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-ASSET-409-005"));

        commandService.archiveCoordinateSystem(firstSiteId, source.id(), source.version());
        assertThat(queryService.findCoordinateSystems(
                firstSiteId, null, "ARCHIVED", 1, 20).items())
                .extracting(CoordinateSystemResponse::id)
                .containsExactly(source.id());
        assertThat(auditActions()).contains(
                "CREATE_COORDINATE_SYSTEM", "CREATE_COORDINATE_TRANSFORM",
                "UPDATE_COORDINATE_TRANSFORM", "PUBLISH_COORDINATE_TRANSFORM",
                "ARCHIVE_COORDINATE_SYSTEM");
    }

    @Test
    void 控制器必须发布计划约定的矿区资产路径() {
        assertThat(MineAssetController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/mine/sites/{siteId}");
        assertThat(method("findVehicles").getAnnotation(GetMapping.class).value())
                .containsExactly("/vehicles");
        assertThat(method("findLocationTags").getAnnotation(GetMapping.class).value())
                .containsExactly("/location-tags");
        assertThat(method("findCoordinateSystems").getAnnotation(GetMapping.class).value())
                .containsExactly("/coordinate-systems");
        assertThat(method("findCoordinateTransforms").getAnnotation(GetMapping.class).value())
                .containsExactly("/coordinate-transforms");
        assertThat(method("publishCoordinateTransform").getAnnotation(PostMapping.class).value())
                .containsExactly("/coordinate-transforms/{transformId}/publish");
    }

    private java.util.List<String> auditActions() {
        return jdbcTemplate.queryForList("""
                SELECT action
                FROM sys_operation_log
                WHERE module = 'MINE_MANAGEMENT'
                ORDER BY occurred_at, id
                """, String.class);
    }

    private Method method(String name) {
        return Arrays.stream(MineAssetController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private UUID createUser(String suffix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', ?)
                """, id, "mine-asset-it-" + suffix, "矿区资产测试用户" + suffix);
        return id;
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

    /** 单线程集成测试使用的可切换身份。 */
    static final class MutableMineRequestIdentity implements MineRequestIdentity {
        private UUID userId;

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
