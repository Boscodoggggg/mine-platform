package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.scene.SceneAssetService;
import com.bosco.mine.server.modules.mine.scene.dto.SceneAssetResponse;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验三维资源上传、清单、矿区隔离和归档读取闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(SceneAssetManagementIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SceneAssetManagementIT {

    private static final Path STORAGE_ROOT = temporaryStorageRoot();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private SceneAssetService service;

    @Autowired
    private MineScopeCommandService scopeCommandService;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID firstUserId;
    private UUID secondUserId;
    private UUID firstSiteId;
    private UUID secondSiteId;

    @DynamicPropertySource
    static void sceneStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("mine.scene-storage.root", STORAGE_ROOT::toString);
        registry.add("mine.scene-storage.max-upload-bytes", () -> "1048576");
    }

    @BeforeEach
    void 准备两个隔离矿区() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
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
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'scene-asset-it-%'");
        firstUserId = createUser("first");
        secondUserId = createUser("second");

        identity.use(firstUserId);
        MineOrganizationResponse firstOrganization = scopeCommandService.createOrganization(
                "scene-asset-north", "资源北方集团");
        firstSiteId = scopeCommandService.createSite(
                firstOrganization.id(), "scene-asset-north-pit", "资源北矿",
                "Asia/Shanghai", null).id();

        identity.use(secondUserId);
        MineOrganizationResponse secondOrganization = scopeCommandService.createOrganization(
                "scene-asset-south", "资源南方集团");
        secondSiteId = scopeCommandService.createSite(
                secondOrganization.id(), "scene-asset-south-pit", "资源南矿",
                "Asia/Shanghai", null).id();
        identity.use(firstUserId);
    }

    @Test
    void 同矿摘要应幂等而不同矿区应隔离() throws Exception {
        byte[] glb = minimalGlb();
        SceneAssetResponse first = upload(firstSiteId, "pit.glb", glb);
        SceneAssetResponse repeated = upload(firstSiteId, "renamed.glb", glb);
        SceneAssetResponse tiles = service.upload(
                firstSiteId, new ByteArrayInputStream(minimalTiles()),
                "pit-tiles.zip", "application/zip");

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo("AVAILABLE");
        assertThat(first.files().items()).singleElement().satisfies(file -> {
            assertThat(file.relativePath()).isEqualTo("model.glb");
            assertThat(file.contentType()).isEqualTo("model/gltf-binary");
        });
        assertThat(service.openFile(firstSiteId, first.id(), "model.glb").sizeBytes())
                .isEqualTo(glb.length);
        assertThat(tiles.assetType()).isEqualTo("TILES_3D");
        assertThat(tiles.entryPath()).isEqualTo("tileset.json");
        assertThat(tiles.files().items())
                .extracting(file -> file.relativePath())
                .containsExactly("models/tile.glb", "tileset.json");

        identity.use(secondUserId);
        SceneAssetResponse foreign = upload(secondSiteId, "pit.glb", glb);
        assertThat(foreign.id()).isNotEqualTo(first.id());
        assertThatThrownBy(() -> service.findAsset(secondSiteId, first.id(), 1, 100))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-ASSET-404-001"));

        identity.use(firstUserId);
        assertThat(service.findAssets(firstSiteId, null, 1, 20).items())
                .extracting(SceneAssetResponse::id)
                .containsExactlyInAnyOrder(first.id(), tiles.id());
    }

    @Test
    void 归档资源仍应允许历史发布版本读取而未知清单路径应拒绝() throws Exception {
        SceneAssetResponse asset = upload(firstSiteId, "history.glb", minimalGlb());
        UUID publishedVersionId = publishSceneReferencing(asset.id());
        SceneAssetResponse archived = service.archive(
                firstSiteId, asset.id(), asset.version(), "archive-history-asset");
        SceneAssetResponse replayed = service.archive(
                firstSiteId, asset.id(), asset.version(), "archive-history-asset");

        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(archived.version()).isEqualTo(asset.version() + 1);
        assertThat(replayed).isEqualTo(archived);
        assertThat(Files.readAllBytes(
                service.openFile(firstSiteId, asset.id(), "model.glb").path()))
                .isEqualTo(minimalGlb());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM mine_scene_version WHERE id = ?",
                String.class, publishedVersionId)).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> service.openFile(firstSiteId, asset.id(), "not-in-manifest.glb"))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-ASSET-404-002"));
        assertThatThrownBy(() -> service.archive(
                firstSiteId, asset.id(), asset.version(), "archive-stale-asset"))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-ASSET-409-001"));
        assertThatThrownBy(() -> service.archive(
                firstSiteId, asset.id(), archived.version(), "archive-history-asset"))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCENE-409-005"));
    }

    private SceneAssetResponse upload(UUID siteId, String fileName, byte[] content) {
        return service.upload(
                siteId, new ByteArrayInputStream(content), fileName, "model/gltf-binary");
    }

    private UUID createUser(String suffix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name, status)
                VALUES (?, ?, ?, ?, 'ENABLED')
                """, id, "scene-asset-it-" + suffix + "-" + UUID.randomUUID(),
                "{noop}unused", "三维资源测试用户");
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

    private byte[] minimalTiles() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("tileset.json"));
            zip.write("""
                    {"asset":{"version":"1.1"},"root":{
                      "boundingVolume":{"box":[0,0,0,1,0,0,0,1,0,0,0,1]},
                      "geometricError":0,"content":{"uri":"models/tile.glb"}
                    }}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("models/tile.glb"));
            zip.write(minimalGlb());
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private UUID publishSceneReferencing(UUID assetId) {
        UUID organizationId = jdbcTemplate.queryForObject(
                "SELECT organization_id FROM mine_site WHERE id = ?", UUID.class, firstSiteId);
        UUID coordinateId = UUID.randomUUID();
        UUID sceneId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y, min_z, max_z,
                    created_by, updated_by
                ) VALUES (?, ?, ?, 'scene-history-coordinate', '历史场景统一坐标',
                          'LOCAL_CARTESIAN', 'METER', TRUE,
                          0, 10000, 0, 10000, -1000, 3000, ?, ?)
                """, coordinateId, organizationId, firstSiteId, firstUserId, firstUserId);
        jdbcTemplate.update("""
                INSERT INTO mine_scene (
                    id, organization_id, site_id, scene_code, name,
                    created_by, updated_by
                ) VALUES (?, ?, ?, 'history-scene', '历史发布场景', ?, ?)
                """, sceneId, organizationId, firstSiteId, firstUserId, firstUserId);
        jdbcTemplate.update("""
                INSERT INTO mine_scene_version (
                    id, organization_id, site_id, scene_id, coordinate_system_id,
                    revision_number, coordinate_mode,
                    origin_x, origin_y, origin_z,
                    heading_degrees, pitch_degrees, roll_degrees, scale,
                    default_camera, fallback_viewport, created_by
                ) VALUES (?, ?, ?, ?, ?, 1, 'LOCAL_ENU',
                          0, 0, 0, 0, 0, 0, 1,
                          '{}'::jsonb, '{}'::jsonb, ?)
                """, versionId, organizationId, firstSiteId, sceneId, coordinateId, firstUserId);
        jdbcTemplate.update("""
                INSERT INTO mine_scene_layer (
                    id, organization_id, site_id, scene_version_id,
                    layer_code, order_number, layer_type, asset_id
                ) VALUES (?, ?, ?, ?, 'history-model', 1, 'STATIC_GLB', ?)
                """, UUID.randomUUID(), organizationId, firstSiteId, versionId, assetId);
        jdbcTemplate.update("""
                UPDATE mine_scene_version
                SET status = 'PUBLISHED',
                    content_hash = 'sha256:' || repeat('a', 64),
                    published_at = CURRENT_TIMESTAMP,
                    published_by = ?
                WHERE id = ?
                """, firstUserId, versionId);
        return versionId;
    }

    private static Path temporaryStorageRoot() {
        try {
            return Files.createTempDirectory("mine-scene-asset-it-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /**
     * 提供可切换的请求身份，避免集成测试依赖 HTTP 会话。
     */
    @TestConfiguration
    static class IdentityTestConfiguration {

        /** 创建测试请求身份。 */
        @Bean
        @Primary
        MutableMineRequestIdentity mutableMineRequestIdentity() {
            return new MutableMineRequestIdentity();
        }
    }

    /**
     * 测试使用的可切换请求身份。
     */
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
