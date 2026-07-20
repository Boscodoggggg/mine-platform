package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemResponse;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.scene.SceneAssetService;
import com.bosco.mine.server.modules.mine.scene.SceneCommandService;
import com.bosco.mine.server.modules.mine.scene.SceneRuntimeService;
import com.bosco.mine.server.modules.mine.scene.dto.SceneLayerRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneResponse;
import com.bosco.mine.server.modules.mine.scene.dto.SceneRuntimeResponse;
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
 * 使用真实 PostgreSQL 和内容寻址文件系统验证最后正确场景运行快照。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(SceneRuntimeIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SceneRuntimeIT {

    private static final Path STORAGE_ROOT = temporaryStorageRoot();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private SceneRuntimeService runtimeService;

    @Autowired
    private SceneCommandService commandService;

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

    @DynamicPropertySource
    static void sceneStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("mine.scene-storage.root", STORAGE_ROOT::toString);
    }

    @BeforeEach
    void 准备隔离矿区和统一坐标系() {
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
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'scene-runtime-it-%'");
        firstUserId = createUser("first");
        secondUserId = createUser("second");

        identity.use(firstUserId);
        MineOrganizationResponse firstOrganization = scopeCommandService.createOrganization(
                "scene-runtime-north", "运行场景北方集团");
        firstSiteId = scopeCommandService.createSite(
                firstOrganization.id(), "scene-runtime-north-pit", "运行场景北矿",
                "Asia/Shanghai", null).id();
        firstCoordinate = coordinate(firstSiteId, "north-unified", "北矿统一坐标");

        identity.use(secondUserId);
        MineOrganizationResponse secondOrganization = scopeCommandService.createOrganization(
                "scene-runtime-south", "运行场景南方集团");
        secondSiteId = scopeCommandService.createSite(
                secondOrganization.id(), "scene-runtime-south-pit", "运行场景南矿",
                "Asia/Shanghai", null).id();
        coordinate(secondSiteId, "south-unified", "南矿统一坐标");
        identity.use(firstUserId);
    }

    @Test
    void 无默认场景和默认未发布时必须返回固定降级原因() {
        SceneRuntimeResponse noDefault = runtimeService.load(firstSiteId);
        assertThat(noDefault.status()).isEqualTo("DEGRADED");
        assertThat(noDefault.reason()).isEqualTo("NO_DEFAULT_SCENE");
        assertThat(noDefault.snapshot()).isNull();

        SceneResponse scene = createScene("unpublished-scene", "未发布默认场景");
        commandService.setDefault(firstSiteId, scene.id(), scene.version(), "default-unpublished");

        SceneRuntimeResponse unpublished = runtimeService.load(firstSiteId);
        assertThat(unpublished.status()).isEqualTo("DEGRADED");
        assertThat(unpublished.reason()).isEqualTo("DEFAULT_SCENE_UNPUBLISHED");
        assertThat(unpublished.snapshot()).isNull();
    }

    @Test
    void 完整发布快照必须有序且只暴露同源受权资源入口() throws Exception {
        var asset = uploadAsset("north-runtime.glb");
        SceneResponse scene = createScene("north-runtime", "北矿运行场景");
        SceneVersionResponse published = publish(
                scene, "LOCAL_ENU", 100D, 200D, 30D,
                List.of(gridLayer(2), staticLayer(asset.id())));
        SceneResponse defaultScene = commandService.setDefault(
                firstSiteId, scene.id(), scene.version(), "default-runtime");

        SceneRuntimeResponse response = runtimeService.load(firstSiteId);
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.reason()).isEqualTo("NONE");
        assertThat(response.siteId()).isEqualTo(firstSiteId);
        assertThat(response.snapshot()).isNotNull();
        assertThat(response.snapshot().sceneId()).isEqualTo(defaultScene.id());
        assertThat(response.snapshot().versionId()).isEqualTo(published.id());
        assertThat(response.snapshot().schemaVersion()).isEqualTo(1);
        assertThat(response.snapshot().contentHash()).isEqualTo(published.contentHash());
        assertThat(response.snapshot().coordinateMode()).isEqualTo("LOCAL_ENU");
        assertThat(response.snapshot().originX()).isEqualTo(100D);
        assertThat(response.snapshot().layerCount()).isEqualTo(2);
        assertThat(response.snapshot().layers())
                .extracting(SceneRuntimeResponse.RuntimeLayer::orderNumber)
                .containsExactly(1, 2);
        SceneRuntimeResponse.RuntimeAsset runtimeAsset =
                response.snapshot().layers().getFirst().asset();
        assertThat(runtimeAsset.assetId()).isEqualTo(asset.id());
        assertThat(runtimeAsset.entryUrl()).isEqualTo(
                "/api/v1/mine/sites/" + firstSiteId
                        + "/scene-assets/" + asset.id() + "/files/model.glb");
        assertThat(runtimeAsset.entryUrl()).doesNotStartWith("http");

        String serialized = objectMapper.writeValueAsString(response);
        assertThat(serialized)
                .doesNotContain(STORAGE_ROOT.toString(), "createdBy", "publishedBy", "storageKey");
    }

    @Test
    void 资源归档和错误草稿不得破坏最后正确发布快照() {
        var asset = uploadAsset("archived-runtime.glb");
        SceneResponse scene = createScene("archived-runtime", "归档资源运行场景");
        SceneVersionResponse published = publish(
                scene, "LOCAL_ENU", 0D, 0D, 0D,
                List.of(staticLayer(asset.id())));
        commandService.setDefault(firstSiteId, scene.id(), scene.version(), "default-archived");

        SceneVersionResponse draft = commandService.createNextDraft(firstSiteId, scene.id());
        assetService.archive(firstSiteId, asset.id(), asset.version(), "archive-runtime-asset");
        commandService.updateDraft(
                firstSiteId, scene.id(), draft.id(),
                versionRequest("WGS84_ANCHOR", 116.4D, 39.9D, 80D, draft.version()));

        SceneRuntimeResponse response = runtimeService.load(firstSiteId);
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.snapshot().versionId()).isEqualTo(published.id());
        assertThat(response.snapshot().layers().getFirst().asset().assetId()).isEqualTo(asset.id());
    }

    @Test
    void 半包和存储故障必须降级且跨矿区访问必须拒绝() throws Exception {
        var asset = uploadAsset("broken-runtime.glb");
        SceneResponse scene = createScene("broken-runtime", "故障运行场景");
        SceneVersionResponse published = publish(
                scene, "WGS84_ANCHOR", 116.4D, 39.9D, 80D,
                List.of(staticLayer(asset.id())));
        commandService.setDefault(firstSiteId, scene.id(), scene.version(), "default-broken");

        jdbcTemplate.execute("""
                ALTER TABLE mine_scene_version
                DISABLE TRIGGER trg_mine_guard_scene_version_mutation
                """);
        try {
            jdbcTemplate.update(
                    "UPDATE mine_scene_version SET content_hash = ? WHERE id = ?",
                    "sha256:" + "0".repeat(64), published.id());
            SceneRuntimeResponse corrupt = runtimeService.load(firstSiteId);
            assertThat(corrupt.status()).isEqualTo("DEGRADED");
            assertThat(corrupt.reason()).isEqualTo("INVALID_PUBLISHED_SNAPSHOT");
            assertThat(corrupt.snapshot()).isNull();
            jdbcTemplate.update(
                    "UPDATE mine_scene_version SET content_hash = ? WHERE id = ?",
                    published.contentHash(), published.id());
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE mine_scene_version
                    ENABLE TRIGGER trg_mine_guard_scene_version_mutation
                    """);
        }
        Path entry = STORAGE_ROOT.resolve("objects")
                .resolve(asset.contentDigest().substring("sha256:".length()))
                .resolve("model.glb");
        Files.delete(entry);
        SceneRuntimeResponse unavailable = runtimeService.load(firstSiteId);
        assertThat(unavailable.status()).isEqualTo("DEGRADED");
        assertThat(unavailable.reason()).isEqualTo("SCENE_STORAGE_UNAVAILABLE");
        assertThat(unavailable.snapshot()).isNull();

        identity.use(secondUserId);
        assertThatThrownBy(() -> runtimeService.load(firstSiteId))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-403-002"));
    }

    private SceneResponse createScene(String code, String name) {
        return commandService.createScene(
                firstSiteId, new SceneRequest(code, name, null, 0L));
    }

    private SceneVersionResponse publish(
            SceneResponse scene,
            String coordinateMode,
            double originX,
            double originY,
            double originZ,
            List<SceneLayerRequest> layers
    ) {
        SceneVersionResponse draft = commandService.createDraft(
                firstSiteId, scene.id(),
                versionRequest(coordinateMode, originX, originY, originZ, 0L));
        SceneVersionResponse layered = commandService.replaceLayers(
                firstSiteId, scene.id(), draft.id(), draft.version(), layers);
        return commandService.publish(
                firstSiteId, scene.id(), draft.id(), layered.version(),
                "publish-" + scene.sceneCode());
    }

    private SceneVersionRequest versionRequest(
            String coordinateMode,
            double originX,
            double originY,
            double originZ,
            long version
    ) {
        try {
            return new SceneVersionRequest(
                    firstCoordinate.id(), coordinateMode,
                    originX, originY, originZ, 0D, 0D, 0D, 1D,
                    objectMapper.readTree("{\"position\":[100,200,300]}"),
                    objectMapper.readTree(
                            "{\"minX\":0,\"maxX\":1000,\"minY\":0,\"maxY\":800}"),
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

    private com.bosco.mine.server.modules.mine.scene.dto.SceneAssetResponse uploadAsset(
            String filename
    ) {
        return assetService.upload(
                firstSiteId, new ByteArrayInputStream(minimalGlb(filename)),
                filename, "model/gltf-binary");
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
                """, id, "scene-runtime-it-" + suffix + "-" + UUID.randomUUID(),
                "{noop}unused", "场景运行测试用户");
        return id;
    }

    private byte[] minimalGlb(String marker) {
        String jsonText = "{\"name\":\"" + marker + "\"}";
        jsonText += " ".repeat((4 - jsonText.length() % 4) % 4);
        byte[] json = jsonText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
            return Files.createTempDirectory("mine-scene-runtime-it-");
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
