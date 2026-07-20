package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemResponse;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.scene.SceneAssetService;
import com.bosco.mine.server.modules.mine.scene.SceneCommandService;
import com.bosco.mine.server.modules.mine.scene.SceneQueryService;
import com.bosco.mine.server.modules.mine.scene.dto.SceneLayerRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneResponse;
import com.bosco.mine.server.modules.mine.scene.dto.SceneVersionRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneVersionResponse;
import com.bosco.mine.server.modules.mine.service.MineAssetCommandService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验稳定场景、草稿、图层、发布和持久幂等治理。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(SceneManagementIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SceneManagementIT {

    private static final Path STORAGE_ROOT = temporaryStorageRoot();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private SceneCommandService commandService;

    @Autowired
    private SceneQueryService queryService;

    @Autowired
    private SceneAssetService assetService;

    @Autowired
    private MineAssetCommandService mineAssetCommandService;

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
    private CoordinateSystemResponse firstCoordinate;
    private CoordinateSystemResponse secondCoordinate;

    @DynamicPropertySource
    static void sceneStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("mine.scene-storage.root", STORAGE_ROOT::toString);
    }

    @BeforeEach
    void 准备两个隔离矿区和统一坐标系() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_scene_action_idempotency,
                    mine_scene_layer,
                    mine_scene_version,
                    mine_scene,
                    mine_scene_asset_file,
                    mine_scene_asset,
                    mine_coordinate_transform_version,
                    mine_coordinate_system,
                    mine_user_scope_grant,
                    mine_site,
                    mine_organization,
                    sys_operation_log
                CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'scene-management-it-%'");
        firstUserId = createUser("first");
        secondUserId = createUser("second");

        identity.use(firstUserId);
        MineOrganizationResponse firstOrganization = scopeCommandService.createOrganization(
                "scene-management-north", "场景北方集团");
        firstSiteId = scopeCommandService.createSite(
                firstOrganization.id(), "scene-management-north-pit", "场景北矿",
                "Asia/Shanghai", null).id();
        firstCoordinate = coordinate(firstSiteId, "north-unified", "北矿统一坐标");

        identity.use(secondUserId);
        MineOrganizationResponse secondOrganization = scopeCommandService.createOrganization(
                "scene-management-south", "场景南方集团");
        secondSiteId = scopeCommandService.createSite(
                secondOrganization.id(), "scene-management-south-pit", "场景南矿",
                "Asia/Shanghai", null).id();
        secondCoordinate = coordinate(secondSiteId, "south-unified", "南矿统一坐标");
        identity.use(firstUserId);
    }

    @Test
    void 稳定场景应支持唯一编码单一默认乐观修改和幂等归档() {
        SceneResponse first = commandService.createScene(
                firstSiteId, new SceneRequest("north-main", "北矿主场景", "主生产区", 0));
        SceneResponse second = commandService.createScene(
                firstSiteId, new SceneRequest("north-backup", "北矿备用场景", null, 0));

        assertThatThrownBy(() -> commandService.createScene(
                firstSiteId, new SceneRequest("north-main", "重复场景", null, 0)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-409-001"));

        SceneResponse defaultFirst = commandService.setDefault(
                firstSiteId, first.id(), first.version(), "default-first");
        SceneResponse replay = commandService.setDefault(
                firstSiteId, first.id(), first.version(), "default-first");
        assertThat(replay).isEqualTo(defaultFirst);
        assertThatThrownBy(() -> commandService.setDefault(
                firstSiteId, second.id(), second.version(), "default-first"))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-409-005"));

        SceneResponse updated = commandService.updateScene(
                firstSiteId, first.id(),
                new SceneRequest("north-main", "北矿主生产场景", "已调整", defaultFirst.version()));
        assertThatThrownBy(() -> commandService.updateScene(
                firstSiteId, first.id(),
                new SceneRequest("north-main", "并发覆盖", null, defaultFirst.version())))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-409-002"));
        assertThatThrownBy(() -> commandService.archiveScene(
                firstSiteId, first.id(), updated.version(), "archive-default"))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-409-003"));

        SceneResponse defaultSecond = commandService.setDefault(
                firstSiteId, second.id(), second.version(), "default-second");
        SceneResponse archived = commandService.archiveScene(
                firstSiteId, first.id(), updated.version() + 1, "archive-first");
        assertThat(defaultSecond.defaultScene()).isTrue();
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(commandService.archiveScene(
                firstSiteId, first.id(), updated.version() + 1, "archive-first"))
                .isEqualTo(archived);
        assertThat(queryService.findScenes(firstSiteId, null, null, 1, 20).total()).isEqualTo(2);
    }

    @Test
    void 草稿应校验跨矿引用并确定性发布不可变版本() {
        SceneResponse scene = commandService.createScene(
                firstSiteId, new SceneRequest("pit-digital-twin", "矿区数字孪生", null, 0));
        SceneVersionResponse draft = commandService.createDraft(
                firstSiteId, scene.id(), versionRequest(firstCoordinate.id(), 0));

        assertThatThrownBy(() -> commandService.createDraft(
                firstSiteId, scene.id(), versionRequest(secondCoordinate.id(), 0)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-422-003"));

        UUID assetId = uploadAsset(firstSiteId, "north.glb");
        identity.use(secondUserId);
        UUID foreignAssetId = uploadAsset(secondSiteId, "south.glb");
        identity.use(firstUserId);
        assertThatThrownBy(() -> commandService.replaceLayers(
                firstSiteId, scene.id(), draft.id(), draft.version(),
                List.of(staticLayer(foreignAssetId), gridLayer(2))))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-422-004"));

        SceneVersionResponse layered = commandService.replaceLayers(
                firstSiteId, scene.id(), draft.id(), draft.version(),
                List.of(gridLayer(2), staticLayer(assetId)));
        SceneVersionResponse published = commandService.publish(
                firstSiteId, scene.id(), draft.id(), layered.version(), "publish-v1");
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.contentHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(published.layers()).extracting(SceneVersionResponse.SceneLayerResponse::orderNumber)
                .containsExactly(1, 2);
        assertThat(commandService.publish(
                firstSiteId, scene.id(), draft.id(), layered.version(), "publish-v1"))
                .isEqualTo(published);
        assertThatThrownBy(() -> commandService.updateDraft(
                firstSiteId, scene.id(), draft.id(), versionRequest(firstCoordinate.id(), published.version())))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-409-004"));

        SceneVersionResponse nextDraft = commandService.createNextDraft(firstSiteId, scene.id());
        assertThat(nextDraft.revisionNumber()).isEqualTo(2);
        assertThat(nextDraft.layers()).hasSize(2);
        SceneVersionResponse nextPublished = commandService.publish(
                firstSiteId, scene.id(), nextDraft.id(), nextDraft.version(), "publish-v2");
        assertThat(nextPublished.contentHash()).isEqualTo(published.contentHash());
        assertThat(queryService.findVersion(firstSiteId, scene.id(), published.id()).status())
                .isEqualTo("SUPERSEDED");

        SceneVersionResponse failingDraft = commandService.createNextDraft(firstSiteId, scene.id());
        var asset = assetService.findAsset(firstSiteId, assetId, 1, 20);
        assetService.archive(firstSiteId, assetId, asset.version());
        assertThatThrownBy(() -> commandService.publish(
                firstSiteId, scene.id(), failingDraft.id(), failingDraft.version(), "publish-invalid"))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-422-004"));
        assertThat(queryService.findCurrentPublished(firstSiteId, scene.id()).id())
                .isEqualTo(nextPublished.id());
    }

    private SceneVersionRequest versionRequest(UUID coordinateSystemId, long version) {
        try {
            return new SceneVersionRequest(
                    coordinateSystemId, "LOCAL_ENU",
                    0D, 0D, 0D, 0D, 0D, 0D, 1D,
                    objectMapper.readTree("{\"position\":[100,200,300]}"),
                    objectMapper.readTree("{\"minX\":0,\"maxX\":1000,\"minY\":0,\"maxY\":800}"),
                    version);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SceneLayerRequest staticLayer(UUID assetId) {
        return new SceneLayerRequest(
                "pit-model", 1, "STATIC_GLB", assetId, true,
                1D, 0D, 1_000_000D,
                0D, 0D, 0D, 0D, 0D, 0D, 1D, 0,
                objectMapper.createObjectNode());
    }

    private SceneLayerRequest gridLayer(int order) {
        return new SceneLayerRequest(
                "coordinate-grid", order, "GRID", null, true,
                0.5D, 0D, 100_000D,
                0D, 0D, 0D, 0D, 0D, 0D, 1D, 0,
                objectMapper.createObjectNode());
    }

    private UUID uploadAsset(UUID siteId, String filename) {
        return assetService.upload(
                siteId, new ByteArrayInputStream(minimalGlb()),
                filename, "model/gltf-binary").id();
    }

    private CoordinateSystemResponse coordinate(UUID siteId, String code, String name) {
        return mineAssetCommandService.createCoordinateSystem(
                siteId,
                new CoordinateSystemRequest(
                        code, name, "LOCAL_CARTESIAN", "METER", null, true,
                        0D, 10_000D, 0D, 10_000D, -1_000D, 3_000D));
    }

    private UUID createUser(String suffix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name, status)
                VALUES (?, ?, ?, ?, 'ENABLED')
                """, id, "scene-management-it-" + suffix + "-" + UUID.randomUUID(),
                "{noop}unused", "场景治理测试用户");
        return id;
    }

    private byte[] minimalGlb() {
        byte[] json = "{}  ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ByteBuffer.allocate(20 + json.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67)
                .putInt(2)
                .putInt(20 + json.length)
                .putInt(json.length)
                .putInt(0x4E4F534A)
                .put(json)
                .array();
    }

    private static Path temporaryStorageRoot() {
        try {
            return Files.createTempDirectory("mine-scene-management-it-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /** 提供可切换的测试请求身份。 */
    @TestConfiguration
    static class IdentityTestConfiguration {

        /** 创建测试请求身份。 */
        @Bean
        @Primary
        MutableMineRequestIdentity mutableMineRequestIdentity() {
            return new MutableMineRequestIdentity();
        }
    }

    /** 测试使用的可切换请求身份。 */
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
