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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验车辆、标签、坐标系和转换版本约束。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MineAssetConstraintIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID firstSiteId;
    private UUID secondSiteId;

    @BeforeEach
    void 准备两个矿区() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_coordinate_transform_version,
                    mine_coordinate_system,
                    mine_location_tag,
                    mine_vehicle,
                    mine_user_scope_grant,
                    mine_site,
                    mine_organization
                CASCADE
                """);
        organizationId = UUID.randomUUID();
        firstSiteId = UUID.randomUUID();
        secondSiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, 'asset-group', '资产约束测试集团')
                """, organizationId);
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, 'east-pit', '东矿', 'Asia/Shanghai'),
                       (?, ?, 'west-pit', '西矿', 'Asia/Shanghai')
                """, firstSiteId, organizationId, secondSiteId, organizationId);
    }

    @Test
    void 车辆和标签编码应按矿区隔离且拒绝非法生命周期字段() {
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type,
                    rated_load_tons
                ) VALUES (?, ?, ?, 'truck-001', '东矿一号车', 'HAUL_TRUCK', 90.5),
                         (?, ?, ?, 'truck-001', '西矿一号车', 'HAUL_TRUCK', 95)
                """, UUID.randomUUID(), organizationId, firstSiteId,
                UUID.randomUUID(), organizationId, secondSiteId);
        jdbcTemplate.update("""
                INSERT INTO mine_location_tag (
                    id, organization_id, site_id, tag_code, vendor, model, battery_percent
                ) VALUES (?, ?, ?, 'tag-001', '模拟厂商', 'SIM-UWB', 88),
                         (?, ?, ?, 'tag-001', '模拟厂商', 'SIM-UWB', 76)
                """, UUID.randomUUID(), organizationId, firstSiteId,
                UUID.randomUUID(), organizationId, secondSiteId);

        assertRejected("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, 'truck-001', '重复车辆', 'HAUL_TRUCK')
                """, UUID.randomUUID(), organizationId, firstSiteId);
        assertRejected("""
                INSERT INTO mine_location_tag (
                    id, organization_id, site_id, tag_code, battery_percent
                ) VALUES (?, ?, ?, 'tag-002', 101)
                """, UUID.randomUUID(), organizationId, firstSiteId);
        assertRejected("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type,
                    status, archived_at
                ) VALUES (?, ?, ?, 'truck-002', '非法归档车辆', 'HAUL_TRUCK',
                          'ARCHIVED', NULL)
                """, UUID.randomUUID(), organizationId, firstSiteId);
    }

    @Test
    void 每个矿区只能有一个活动统一坐标系且坐标边界必须有序() {
        UUID unifiedId = coordinateSystem(
                firstSiteId, "mine-local", "LOCAL_CARTESIAN", "METER", true,
                0, 10_000, 0, 8_000);
        assertThat(unifiedId).isNotNull();

        assertRejected("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y
                ) VALUES (?, ?, ?, 'mine-local-2', '第二统一坐标系',
                          'LOCAL_CARTESIAN', 'METER', TRUE, 0, 100, 0, 100)
                """, UUID.randomUUID(), organizationId, firstSiteId);
        assertRejected("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y
                ) VALUES (?, ?, ?, 'invalid-bounds', '非法边界',
                          'LOCAL_CARTESIAN', 'METER', FALSE, 100, 0, 0, 100)
                """, UUID.randomUUID(), organizationId, firstSiteId);
        assertRejected("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y
                ) VALUES (?, ?, ?, 'invalid-wgs84', '非法经纬度',
                          'WGS84', 'DEGREE', FALSE, -181, 180, -90, 90)
                """, UUID.randomUUID(), organizationId, firstSiteId);
    }

    @Test
    void 坐标转换必须同矿版本化且发布后参数摘要和有效期不可修改() {
        UUID sourceId = coordinateSystem(
                firstSiteId, "vendor-local", "LOCAL_CARTESIAN", "METER", false,
                -1_000, 1_000, -1_000, 1_000);
        UUID targetId = coordinateSystem(
                firstSiteId, "mine-local", "LOCAL_CARTESIAN", "METER", true,
                0, 10_000, 0, 8_000);
        UUID crossSiteTargetId = coordinateSystem(
                secondSiteId, "west-local", "LOCAL_CARTESIAN", "METER", true,
                0, 5_000, 0, 4_000);
        UUID transformId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_transform_version (
                    id, organization_id, site_id,
                    source_coordinate_system_id, target_coordinate_system_id,
                    version_number, algorithm, parameters, valid_from,
                    status, content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, 1, 'IDENTITY', '{}'::jsonb,
                          CURRENT_TIMESTAMP, 'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, transformId, organizationId, firstSiteId, sourceId, targetId,
                "sha256:" + "a".repeat(64));

        assertRejected("""
                UPDATE mine_coordinate_transform_version
                   SET parameters = '{"offset":1}'::jsonb
                 WHERE id = ?
                """, transformId);
        assertRejected("""
                UPDATE mine_coordinate_transform_version
                   SET content_hash = ?
                 WHERE id = ?
                """, "sha256:" + "b".repeat(64), transformId);
        assertRejected("""
                UPDATE mine_coordinate_transform_version
                   SET valid_from = valid_from + INTERVAL '1 second'
                 WHERE id = ?
                """, transformId);
        assertRejected("""
                UPDATE mine_coordinate_transform_version
                   SET valid_to = CURRENT_TIMESTAMP + INTERVAL '1 hour'
                 WHERE id = ?
                """, transformId);
        assertRejected("""
                DELETE FROM mine_coordinate_transform_version
                 WHERE id = ?
                """, transformId);
        assertRejected("""
                INSERT INTO mine_coordinate_transform_version (
                    id, organization_id, site_id,
                    source_coordinate_system_id, target_coordinate_system_id,
                    version_number, algorithm, parameters, valid_from
                ) VALUES (?, ?, ?, ?, ?, 1, 'IDENTITY', '{}'::jsonb, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), organizationId, firstSiteId, sourceId, crossSiteTargetId);
        assertRejected("""
                INSERT INTO mine_coordinate_transform_version (
                    id, organization_id, site_id,
                    source_coordinate_system_id, target_coordinate_system_id,
                    version_number, algorithm, parameters, valid_from
                ) VALUES (?, ?, ?, ?, ?, 3, 'AFFINE_3D',
                          '{"matrix":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,"invalid"]}'::jsonb,
                          CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), organizationId, firstSiteId, sourceId, targetId);
        assertRejected("""
                INSERT INTO mine_coordinate_transform_version (
                    id, organization_id, site_id,
                    source_coordinate_system_id, target_coordinate_system_id,
                    version_number, algorithm, parameters, valid_from
                ) VALUES (?, ?, ?, ?, ?, 4, 'WGS84_ENU', '{}'::jsonb, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), organizationId, firstSiteId, sourceId, targetId);
        assertRejected("""
                UPDATE mine_coordinate_system
                   SET max_x = max_x + 1
                 WHERE id = ?
                """, sourceId);

        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_transform_version (
                    id, organization_id, site_id,
                    source_coordinate_system_id, target_coordinate_system_id,
                    version_number, algorithm, parameters, valid_from
                ) VALUES (?, ?, ?, ?, ?, 2, 'AFFINE_3D',
                          '{"matrix":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}'::jsonb,
                          CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), organizationId, firstSiteId, sourceId, targetId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_coordinate_transform_version
                WHERE source_coordinate_system_id = ? AND target_coordinate_system_id = ?
                """, Long.class, sourceId, targetId)).isEqualTo(2L);
    }

    @Test
    void 转换版本必须递增且已发布有效期不能重叠() {
        UUID sourceId = coordinateSystem(
                firstSiteId, "sequence-source", "LOCAL_CARTESIAN", "METER", false,
                -1_000, 1_000, -1_000, 1_000);
        UUID targetId = coordinateSystem(
                firstSiteId, "sequence-target", "LOCAL_CARTESIAN", "METER", true,
                0, 10_000, 0, 8_000);
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_transform_version (
                    id, organization_id, site_id,
                    source_coordinate_system_id, target_coordinate_system_id,
                    version_number, algorithm, parameters, valid_from
                ) VALUES (?, ?, ?, ?, ?, 2, 'IDENTITY', '{}'::jsonb, '2026-01-01T00:00:00Z')
                """, UUID.randomUUID(), organizationId, firstSiteId, sourceId, targetId);

        assertRejected("""
                INSERT INTO mine_coordinate_transform_version (
                    id, organization_id, site_id,
                    source_coordinate_system_id, target_coordinate_system_id,
                    version_number, algorithm, parameters, valid_from
                ) VALUES (?, ?, ?, ?, ?, 1, 'IDENTITY', '{}'::jsonb, '2025-01-01T00:00:00Z')
                """, UUID.randomUUID(), organizationId, firstSiteId, sourceId, targetId);
        insertPublishedTransform(
                sourceId, targetId, 3, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        insertPublishedTransform(
                sourceId, targetId, 4, "2026-02-01T00:00:00Z", "2026-03-01T00:00:00Z");
        assertRejected("""
                INSERT INTO mine_coordinate_transform_version (
                    id, organization_id, site_id,
                    source_coordinate_system_id, target_coordinate_system_id,
                    version_number, algorithm, parameters, valid_from, valid_to,
                    status, content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, 5, 'IDENTITY', '{}'::jsonb,
                          '2026-01-15T00:00:00Z', '2026-02-15T00:00:00Z',
                          'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), organizationId, firstSiteId, sourceId, targetId,
                "sha256:" + "f".repeat(64));
    }

    private UUID coordinateSystem(
            UUID siteId,
            String code,
            String type,
            String unit,
            boolean unified,
            double minX,
            double maxX,
            double minY,
            double maxY
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, siteId, code, code + "坐标系", type, unit,
                unified, minX, maxX, minY, maxY);
        return id;
    }

    private void insertPublishedTransform(
            UUID sourceId,
            UUID targetId,
            int versionNumber,
            String validFrom,
            String validTo
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_transform_version (
                    id, organization_id, site_id,
                    source_coordinate_system_id, target_coordinate_system_id,
                    version_number, algorithm, parameters, valid_from, valid_to,
                    status, content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'IDENTITY', '{}'::jsonb,
                          ?::timestamptz, ?::timestamptz,
                          'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), organizationId, firstSiteId, sourceId, targetId,
                versionNumber, validFrom, validTo, "sha256:" + "e".repeat(64));
    }

    private void assertRejected(String sql, Object... arguments) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, arguments))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
