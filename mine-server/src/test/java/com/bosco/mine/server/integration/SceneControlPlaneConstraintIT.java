package com.bosco.mine.server.integration;

import com.bosco.mine.server.MineServerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验三维场景、版本、图层和资源控制面的数据库不变量。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SceneControlPlaneConstraintIT {

    private static final List<String> SCENE_TABLES = List.of(
            "mine_scene_asset",
            "mine_scene_asset_file",
            "mine_scene",
            "mine_scene_version",
            "mine_scene_layer",
            "mine_scene_action_idempotency");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine_scene_control_plane")
            .withUsername("mine")
            .withPassword("mine-scene-test-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID siteId;
    private UUID otherSiteId;
    private UUID coordinateSystemId;
    private UUID otherCoordinateSystemId;

    @BeforeEach
    void 准备矿区和坐标系() {
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
                    mine_site,
                    mine_organization
                CASCADE
                """);
        organizationId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        otherSiteId = UUID.randomUUID();
        coordinateSystemId = UUID.randomUUID();
        otherCoordinateSystemId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, ?, '三维场景测试组织')
                """, organizationId, "scene-org-" + shortId(organizationId));
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, ?, '三维场景主矿区', 'Asia/Shanghai'),
                       (?, ?, ?, '三维场景其他矿区', 'Asia/Shanghai')
                """, siteId, organizationId, "scene-main-" + shortId(siteId),
                otherSiteId, organizationId, "scene-other-" + shortId(otherSiteId));
        insertCoordinateSystem(coordinateSystemId, siteId, "scene-local-main");
        insertCoordinateSystem(otherCoordinateSystemId, otherSiteId, "scene-local-other");
    }

    @Test
    void 六张场景控制面表及每个字段都必须有中文注释() {
        for (String table : SCENE_TABLES) {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT obj_description(to_regclass(?), 'pg_class')
                    """, String.class, "public." + table))
                    .as("表 %s 的中文注释", table)
                    .matches(".*[\\u4e00-\\u9fff].*");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns column_info
                    WHERE column_info.table_schema = 'public'
                      AND column_info.table_name = ?
                      AND COALESCE(
                            col_description(
                                to_regclass('public.' || column_info.table_name),
                                column_info.ordinal_position
                            ),
                            ''
                          ) !~ '[\\u4e00-\\u9fff]'
                    """, Integer.class, table))
                    .as("表 %s 缺少中文注释的字段数", table)
                    .isZero();
        }
    }

    @Test
    void 三维资源必须校验摘要大小文件清单和入口并保持内容不可变() {
        UUID glbAssetId = insertGlbAsset(siteId, 'a', "main.glb", 128L);
        insertAssetFile(glbAssetId, siteId, "main.glb", 128L, '1');
        makeAssetAvailable(glbAssetId);

        assertRejected("""
                UPDATE mine_scene_asset
                SET content_digest = ?
                WHERE id = ?
                """, digest('b'), glbAssetId);
        assertRejected("""
                INSERT INTO mine_scene_asset (
                    id, organization_id, site_id, asset_type, original_filename,
                    content_type, content_digest, source_size_bytes,
                    expanded_size_bytes, file_count, entry_path
                ) VALUES (?, ?, ?, 'GLB', 'bad.glb', 'model/gltf-binary',
                          'sha256:not-a-digest', 10, 10, 1, 'bad.glb')
                """, UUID.randomUUID(), organizationId, siteId);
        assertRejected("""
                INSERT INTO mine_scene_asset (
                    id, organization_id, site_id, asset_type, original_filename,
                    content_type, content_digest, source_size_bytes,
                    expanded_size_bytes, file_count, entry_path
                ) VALUES (?, ?, ?, 'GLB', 'bad.glb', 'model/gltf-binary',
                          ?, 0, 0, 1, 'bad.glb')
                """, UUID.randomUUID(), organizationId, siteId, digest('c'));
        assertRejected("""
                INSERT INTO mine_scene_asset (
                    id, organization_id, site_id, asset_type, original_filename,
                    content_type, content_digest, source_size_bytes,
                    expanded_size_bytes, file_count, entry_path
                ) VALUES (?, ?, ?, 'GLB', 'bad.glb', 'model/gltf-binary',
                          ?, 10, 10, 1, '../bad.glb')
                """, UUID.randomUUID(), organizationId, siteId, digest('d'));
        assertRejected("""
                INSERT INTO mine_scene_asset (
                    id, organization_id, site_id, asset_type, original_filename,
                    content_type, content_digest, source_size_bytes,
                    expanded_size_bytes, file_count, entry_path
                ) VALUES (?, ?, ?, 'GLB', 'duplicate.glb', 'model/gltf-binary',
                          ?, 128, 128, 1, 'duplicate.glb')
                """, UUID.randomUUID(), organizationId, siteId, digest('a'));

        UUID tilesAssetId = insertTilesAsset(siteId, 'e');
        insertAssetFile(tilesAssetId, siteId, "tileset.json", 80L, '2');
        insertAssetFile(tilesAssetId, siteId, "Models/truck.glb", 120L, '3');
        assertRejectedAssetFile(tilesAssetId, "../secret.txt");
        assertRejectedAssetFile(tilesAssetId, "/absolute.glb");
        assertRejectedAssetFile(tilesAssetId, "models/truck.glb");
        makeAssetAvailable(tilesAssetId);
        assertRejected("""
                DELETE FROM mine_scene_asset_file
                WHERE asset_id = ? AND relative_path = 'tileset.json'
                """, tilesAssetId);
    }

    @Test
    void 场景默认值版本草稿和发布版本必须在矿区内保持唯一() {
        UUID sceneId = insertScene(siteId, "main-scene", true);
        assertRejectedScene(siteId, "main-scene", false);
        assertRejectedScene(siteId, "other-default", true);

        UUID versionId = insertVersion(sceneId, siteId, coordinateSystemId, 1);
        assertRejectedVersion(sceneId, siteId, coordinateSystemId, 2);
        assertRejectedVersion(sceneId, siteId, otherCoordinateSystemId, 2);

        UUID assetId = availableGlbAsset(siteId, 'f');
        insertStaticLayer(versionId, siteId, "terrain", 1, "STATIC_GLB", assetId);
        publishVersion(versionId);

        UUID secondVersionId = insertVersion(sceneId, siteId, coordinateSystemId, 2);
        insertDynamicLayer(secondVersionId, siteId, "locations", 1);
        assertRejectedPublish(secondVersionId);

        jdbcTemplate.update("""
                UPDATE mine_scene_version
                SET status = 'SUPERSEDED', superseded_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, versionId);
        publishVersion(secondVersionId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM mine_scene_version WHERE id = ?
                """, String.class, secondVersionId)).isEqualTo("PUBLISHED");
    }

    @Test
    void 图层必须匹配资源类型作用域唯一顺序及有限显示参数() {
        UUID sceneId = insertScene(siteId, "layer-scene", false);
        UUID versionId = insertVersion(sceneId, siteId, coordinateSystemId, 1);
        UUID glbAssetId = availableGlbAsset(siteId, 'a');
        UUID otherAssetId = availableGlbAsset(otherSiteId, 'b');

        insertStaticLayer(versionId, siteId, "model", 1, "STATIC_GLB", glbAssetId);
        assertRejectedLayer(versionId, siteId, "model", 2, "STATIC_GLB", glbAssetId);
        assertRejectedLayer(versionId, siteId, "second", 1, "STATIC_GLB", glbAssetId);
        assertRejectedLayer(
                versionId, siteId, "wrong-type", 2, "STATIC_3D_TILES", glbAssetId);
        assertRejectedLayer(
                versionId, siteId, "cross-site", 2, "STATIC_GLB", otherAssetId);
        assertRejected("""
                INSERT INTO mine_scene_layer (
                    id, organization_id, site_id, scene_version_id,
                    layer_code, order_number, layer_type,
                    opacity, minimum_distance, maximum_distance,
                    offset_x, offset_y, offset_z,
                    heading_degrees, pitch_degrees, roll_degrees,
                    scale, max_items
                ) VALUES (
                    ?, ?, ?, ?, 'invalid-opacity', 2, 'DYNAMIC_LOCATIONS',
                    'NaN'::DOUBLE PRECISION, 0, 1000,
                    0, 0, 0, 0, 0, 0, 1, 1000
                )
                """, UUID.randomUUID(), organizationId, siteId, versionId);
        assertRejected("""
                INSERT INTO mine_scene_layer (
                    id, organization_id, site_id, scene_version_id,
                    layer_code, order_number, layer_type,
                    opacity, minimum_distance, maximum_distance,
                    offset_x, offset_y, offset_z,
                    heading_degrees, pitch_degrees, roll_degrees,
                    scale, max_items
                ) VALUES (
                    ?, ?, ?, ?, 'invalid-distance', 2, 'DYNAMIC_LOCATIONS',
                    1, 2000, 1000, 0, 0, 0, 0, 0, 0, 1, 1000
                )
                """, UUID.randomUUID(), organizationId, siteId, versionId);
    }

    @Test
    void 已发布和已替代版本及其图层必须不可修改或删除() {
        UUID sceneId = insertScene(siteId, "immutable-scene", false);
        UUID versionId = insertVersion(sceneId, siteId, coordinateSystemId, 1);
        UUID layerId = insertDynamicLayer(versionId, siteId, "locations", 1);
        publishVersion(versionId);

        assertRejected("UPDATE mine_scene_version SET origin_x = 1 WHERE id = ?", versionId);
        assertRejected("DELETE FROM mine_scene_version WHERE id = ?", versionId);
        assertRejected("UPDATE mine_scene_layer SET opacity = 0.5 WHERE id = ?", layerId);
        assertRejected("DELETE FROM mine_scene_layer WHERE id = ?", layerId);
        assertRejectedLayer(
                versionId, siteId, "alarms", 2, "DYNAMIC_ALARMS", null);

        jdbcTemplate.update("""
                UPDATE mine_scene_version
                SET status = 'SUPERSEDED', superseded_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, versionId);
        assertRejected("UPDATE mine_scene_version SET origin_x = 2 WHERE id = ?", versionId);
        assertRejected("DELETE FROM mine_scene_layer WHERE id = ?", layerId);
    }

    private void insertCoordinateSystem(UUID id, UUID scopeSiteId, String code) {
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y, min_z, max_z
                ) VALUES (
                    ?, ?, ?, ?, '场景统一坐标系', 'LOCAL_CARTESIAN', 'METER', TRUE,
                    -100000, 100000, -100000, 100000, -10000, 10000
                )
                """, id, organizationId, scopeSiteId, code);
    }

    private UUID insertGlbAsset(UUID scopeSiteId, char digestCharacter, String entry, long size) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_scene_asset (
                    id, organization_id, site_id, asset_type, original_filename,
                    content_type, content_digest, source_size_bytes,
                    expanded_size_bytes, file_count, entry_path
                ) VALUES (?, ?, ?, 'GLB', ?, 'model/gltf-binary', ?, ?, ?, 1, ?)
                """, id, organizationId, scopeSiteId, entry, digest(digestCharacter),
                size, size, entry);
        return id;
    }

    private UUID insertTilesAsset(UUID scopeSiteId, char digestCharacter) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_scene_asset (
                    id, organization_id, site_id, asset_type, original_filename,
                    content_type, content_digest, source_size_bytes,
                    expanded_size_bytes, file_count, entry_path
                ) VALUES (
                    ?, ?, ?, 'TILES_3D', 'mine-tiles.zip', 'application/zip',
                    ?, 100, 200, 2, 'tileset.json'
                )
                """, id, organizationId, scopeSiteId, digest(digestCharacter));
        return id;
    }

    private UUID availableGlbAsset(UUID scopeSiteId, char digestCharacter) {
        UUID id = insertGlbAsset(scopeSiteId, digestCharacter, "model.glb", 128L);
        insertAssetFile(id, scopeSiteId, "model.glb", 128L, digestCharacter);
        makeAssetAvailable(id);
        return id;
    }

    private void insertAssetFile(
            UUID assetId,
            UUID scopeSiteId,
            String path,
            long size,
            char digestCharacter
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_scene_asset_file (
                    id, organization_id, site_id, asset_id, relative_path,
                    content_type, content_digest, size_bytes
                ) VALUES (?, ?, ?, ?, ?, 'application/octet-stream', ?, ?)
                """, UUID.randomUUID(), organizationId, scopeSiteId, assetId,
                path, digest(digestCharacter), size);
    }

    private void assertRejectedAssetFile(UUID assetId, String path) {
        assertRejected("""
                INSERT INTO mine_scene_asset_file (
                    id, organization_id, site_id, asset_id, relative_path,
                    content_type, content_digest, size_bytes
                ) VALUES (?, ?, ?, ?, ?, 'application/octet-stream', ?, 1)
                """, UUID.randomUUID(), organizationId, siteId, assetId,
                path, digest('9'));
    }

    private void makeAssetAvailable(UUID assetId) {
        jdbcTemplate.update("""
                UPDATE mine_scene_asset
                SET status = 'AVAILABLE'
                WHERE id = ?
                """, assetId);
    }

    private UUID insertScene(UUID scopeSiteId, String code, boolean defaultScene) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_scene (
                    id, organization_id, site_id, scene_code, name, default_scene
                ) VALUES (?, ?, ?, ?, '三维场景', ?)
                """, id, organizationId, scopeSiteId, code, defaultScene);
        return id;
    }

    private void assertRejectedScene(UUID scopeSiteId, String code, boolean defaultScene) {
        assertRejected("""
                INSERT INTO mine_scene (
                    id, organization_id, site_id, scene_code, name, default_scene
                ) VALUES (?, ?, ?, ?, '重复场景', ?)
                """, UUID.randomUUID(), organizationId, scopeSiteId, code, defaultScene);
    }

    private UUID insertVersion(
            UUID sceneId,
            UUID scopeSiteId,
            UUID scopeCoordinateSystemId,
            int revision
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_scene_version (
                    id, organization_id, site_id, scene_id, coordinate_system_id,
                    revision_number, coordinate_mode,
                    origin_x, origin_y, origin_z,
                    heading_degrees, pitch_degrees, roll_degrees, scale,
                    default_camera, fallback_viewport
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, 'LOCAL_ENU',
                    0, 0, 0, 0, 0, 0, 1,
                    '{"position":[0,0,1000]}'::JSONB,
                    '{"center":[0,0],"zoom":1}'::JSONB
                )
                """, id, organizationId, scopeSiteId, sceneId,
                scopeCoordinateSystemId, revision);
        return id;
    }

    private void assertRejectedVersion(
            UUID sceneId,
            UUID scopeSiteId,
            UUID scopeCoordinateSystemId,
            int revision
    ) {
        assertThatThrownBy(() -> insertVersion(
                sceneId, scopeSiteId, scopeCoordinateSystemId, revision))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertStaticLayer(
            UUID versionId,
            UUID scopeSiteId,
            String code,
            int order,
            String type,
            UUID assetId
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_scene_layer (
                    id, organization_id, site_id, scene_version_id,
                    layer_code, order_number, layer_type, asset_id,
                    opacity, minimum_distance, maximum_distance,
                    offset_x, offset_y, offset_z,
                    heading_degrees, pitch_degrees, roll_degrees,
                    scale, max_items
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    1, 0, 1000000, 0, 0, 0, 0, 0, 0, 1, 0
                )
                """, id, organizationId, scopeSiteId, versionId,
                code, order, type, assetId);
        return id;
    }

    private UUID insertDynamicLayer(
            UUID versionId,
            UUID scopeSiteId,
            String code,
            int order
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_scene_layer (
                    id, organization_id, site_id, scene_version_id,
                    layer_code, order_number, layer_type,
                    opacity, minimum_distance, maximum_distance,
                    offset_x, offset_y, offset_z,
                    heading_degrees, pitch_degrees, roll_degrees,
                    scale, max_items
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, 'DYNAMIC_LOCATIONS',
                    1, 0, 1000000, 0, 0, 0, 0, 0, 0, 1, 1000
                )
                """, id, organizationId, scopeSiteId, versionId, code, order);
        return id;
    }

    private void assertRejectedLayer(
            UUID versionId,
            UUID scopeSiteId,
            String code,
            int order,
            String type,
            UUID assetId
    ) {
        assertThatThrownBy(() -> {
            if (type.startsWith("STATIC_")) {
                insertStaticLayer(versionId, scopeSiteId, code, order, type, assetId);
            } else {
                insertDynamicLayer(versionId, scopeSiteId, code, order);
            }
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void publishVersion(UUID versionId) {
        jdbcTemplate.update("""
                UPDATE mine_scene_version
                SET status = 'PUBLISHED', content_hash = ?, published_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, digest('0'), versionId);
    }

    private void assertRejectedPublish(UUID versionId) {
        assertThatThrownBy(() -> publishVersion(versionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertRejected(String sql, Object... arguments) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, arguments))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String digest(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
