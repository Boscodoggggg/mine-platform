package com.bosco.mine.server.integration;

import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.aggregation.repository.AggregationRecomputeRepository;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.model.NotificationChannel;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderRegistry;
import com.bosco.mine.server.modules.notification.repository.NotificationChannelMapper;
import com.bosco.mine.server.modules.notification.security.NotificationLegacySecretMigrator;
import com.bosco.mine.server.modules.notification.security.NotificationSecretCipher;
import com.bosco.mine.server.modules.strategy.repository.RetentionPreviewEvidenceMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验系统基线迁移、关键约束和重复启动行为。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class DatabaseMigrationIT {

    private static final List<String> EXPECTED_TABLES = List.of(
            "sys_user",
            "sys_role",
            "sys_menu",
            "sys_user_role",
            "sys_role_menu",
            "sys_dict_type",
            "sys_dict_item",
            "dev_model_version",
            "dev_model_field",
            "dev_protocol_mapping_version",
            "dev_protocol_field_mapping",
            "dev_protocol_sample",
            "dev_protocol_test_run",
            "dev_config_package",
            "dev_config_release",
            "dev_config_release_target",
            "dev_config_node_status",
            "dev_device_group",
            "dev_device_group_membership",
            "dev_strategy_version",
            "dev_strategy_release",
            "dev_retention_preview_evidence",
            "dev_aggregation_recompute_request",
            "dev_strategy_runtime_snapshot",
            "dev_strategy_runtime_snapshot_item",
            "mine_organization",
            "mine_site",
            "mine_user_scope_grant",
            "mine_vehicle",
            "mine_location_tag",
            "mine_coordinate_system",
            "mine_coordinate_transform_version",
            "mine_person_site_assignment",
            "mine_sensitive_data_key_guard",
            "mine_vehicle_device_binding",
            "mine_location_tag_binding_claim",
            "mine_vehicle_tag_binding",
            "mine_person_tag_binding",
            "mine_person_vehicle_binding",
            "mine_binding_switch_operation",
            "mine_location_runtime_snapshot",
            "mine_location_runtime_snapshot_item",
            "mine_waypoint",
            "mine_waypoint_version",
            "mine_route",
            "mine_route_version",
            "mine_route_version_waypoint",
            "mine_geofence",
            "mine_geofence_version",
            "mine_geofence_vertex",
            "mine_shift_definition",
            "mine_shift_instance",
            "mine_work_order",
            "mine_work_order_status_history",
            "mine_work_order_assignment",
            "mine_operation_spatial_event_queue",
            "mine_operation_runtime_state",
            "mine_operation_candidate",
            "mine_operation_record",
            "mine_operation_evidence",
            "mine_operation_correction",
            "mine_operation_effective_record",
            "mine_operation_outbox",
            "mine_operation_site_sequence",
            "mine_operation_action_idempotency",
            "mine_scene_asset",
            "mine_scene_asset_file",
            "mine_scene",
            "mine_scene_version",
            "mine_scene_layer",
            "mine_scene_action_idempotency",
            "sys_login_log",
            "sys_operation_log",
            "sys_outbox_event",
            "sys_inbox_event",
            "cmd_instance",
            "alarm_event",
            "alarm_acknowledgement",
            "notification_channel",
            "notification_recipient_group",
            "notification_recipient",
            "notification_template",
            "notification_policy",
            "notification_policy_version",
            "notification_policy_step",
            "notification_secret_key_guard"
    );

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private RetentionPreviewEvidenceMapper retentionPreviewEvidenceMapper;

    @Autowired
    private AggregationRecomputeRepository aggregationRecomputeRepository;

    @Autowired
    private NotificationChannelMapper notificationChannelMapper;

    @Autowired
    private NotificationProviderRegistry notificationProviderRegistry;

    @Autowired
    private NotificationLegacySecretMigrator notificationLegacySecretMigrator;

    @Autowired
    private NotificationSecretCipher notificationSecretCipher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 应创建权限审计和消息一致性表() {
        List<String> actualTables = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class);

        assertThat(actualTables).containsAll(EXPECTED_TABLES);
        assertThat(constraintColumns("uk_sys_user_username")).containsExactly("username");
        assertThat(constraintColumns("uk_sys_role_code")).containsExactly("code");
        assertThat(constraintColumns("uk_sys_user_role")).containsExactly("user_id", "role_id");
        assertThat(constraintColumns("uk_sys_role_menu")).containsExactly("role_id", "menu_id");
        assertThat(constraintColumns("pk_sys_inbox_event"))
                .containsExactly("consumer_name", "event_id");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_role", Long.class)).isZero();
    }

    @Test
    void 生产运营动作幂等账本必须具备作用域唯一约束和完整中文注释() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT obj_description('mine_operation_action_idempotency'::regclass, 'pg_class')
                """, String.class)).contains("幂等");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid = 'mine_operation_action_idempotency'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(constraintColumns("uk_mine_operation_action_idempotency_key"))
                .containsExactly(
                        "organization_id", "site_id", "operator_id", "idempotency_key");
    }

    @Test
    void 运营业务序号必须按矿区持久唯一且计数器具备完整中文注释() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT obj_description('mine_operation_site_sequence'::regclass, 'pg_class')
                """, String.class)).contains("业务序号");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid = 'mine_operation_site_sequence'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT col_description(
                    'mine_operation_outbox'::regclass,
                    (SELECT attnum FROM pg_attribute
                     WHERE attrelid = 'mine_operation_outbox'::regclass
                       AND attname = 'business_sequence')
                )
                """, String.class)).contains("连续递增");
        assertThat(constraintColumns("uk_mine_operation_outbox_site_sequence"))
                .containsExactly("site_id", "business_sequence");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_trigger
                WHERE tgname = 'trg_mine_operation_outbox_business_sequence'
                  AND NOT tgisinternal
                """, Long.class)).isOne();
    }

    @Test
    void 作业单状态历史必须具备发生时区间约束和完整中文注释() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT obj_description('mine_work_order_status_history'::regclass, 'pg_class')
                """, String.class)).contains("状态历史");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid = 'mine_work_order_status_history'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(constraintColumns("uk_mine_work_order_status_history_version"))
                .containsExactly("work_order_id", "status_version");
    }

    @Test
    void 组织矿区和数据授权必须具备作用域约束及完整中文注释() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN (
                    'mine_organization', 'mine_site', 'mine_user_scope_grant'
                )
                ORDER BY class.relname
                """, String.class))
                .hasSize(3)
                .allMatch(comment -> comment.matches(".*[\\u4e00-\\u9fff].*"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN (
                    'mine_organization'::regclass,
                    'mine_site'::regclass,
                    'mine_user_scope_grant'::regclass
                )
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(constraintColumns("uk_mine_organization_code"))
                .containsExactly("organization_code");
        assertThat(constraintColumns("uk_mine_site_organization_code"))
                .containsExactly("organization_id", "site_code");
        assertThat(constraintColumns("uk_mine_site_id_organization"))
                .containsExactly("id", "organization_id");
        assertThat(constraintColumns("uk_mine_user_scope_grant_scope"))
                .containsExactly("user_id", "organization_id", "site_id");
        assertThat(constraintColumns("fk_mine_scope_grant_site_organization"))
                .containsExactly("site_id", "organization_id");

        UUID userId = UUID.randomUUID();
        UUID firstOrganizationId = UUID.randomUUID();
        UUID secondOrganizationId = UUID.randomUUID();
        UUID firstSiteId = UUID.randomUUID();
        UUID secondSiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', '矿区作用域测试用户')
                """, userId, "mine-scope-" + userId);
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, 'north-group', '北方矿业集团'), (?, 'south-group', '南方矿业集团')
                """, firstOrganizationId, secondOrganizationId);
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, 'main-pit', '北方一号矿', 'Asia/Shanghai'),
                       (?, ?, 'main-pit', '南方一号矿', 'Asia/Shanghai')
                """, firstSiteId, firstOrganizationId, secondSiteId, secondOrganizationId);

        jdbcTemplate.update("""
                INSERT INTO mine_user_scope_grant (id, user_id, organization_id, site_id)
                VALUES (?, ?, ?, NULL), (?, ?, ?, ?)
                """, UUID.randomUUID(), userId, firstOrganizationId,
                UUID.randomUUID(), userId, secondOrganizationId, secondSiteId);

        assertRejected("""
                INSERT INTO mine_user_scope_grant (id, user_id, organization_id, site_id)
                VALUES (?, ?, ?, NULL)
                """, UUID.randomUUID(), userId, firstOrganizationId);
        assertRejected("""
                INSERT INTO mine_user_scope_grant (id, user_id, organization_id, site_id)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), userId, secondOrganizationId, firstSiteId);
        assertRejected("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, 'NORTH-GROUP', '未归一化编码')
                """, UUID.randomUUID());
        assertRejected("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, 'invalid-site', '非法时区矿区', 'Mars/Olympus')
                """, UUID.randomUUID(), firstOrganizationId);
        assertRejected("""
                UPDATE mine_site
                   SET status = 'ARCHIVED', archived_at = NULL
                 WHERE id = ?
                """, firstSiteId);
    }

    @Test
    void 矿区资产和坐标版本表必须具备完整中文注释() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN (
                    'mine_vehicle', 'mine_location_tag',
                    'mine_coordinate_system', 'mine_coordinate_transform_version'
                )
                ORDER BY class.relname
                """, String.class))
                .hasSize(4)
                .allMatch(comment -> comment.matches(".*[\\u4e00-\\u9fff].*"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN (
                    'mine_vehicle'::regclass,
                    'mine_location_tag'::regclass,
                    'mine_coordinate_system'::regclass,
                    'mine_coordinate_transform_version'::regclass
                )
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(constraintColumns("uk_mine_vehicle_site_code"))
                .containsExactly("site_id", "vehicle_code");
        assertThat(constraintColumns("uk_mine_location_tag_site_code"))
                .containsExactly("site_id", "tag_code");
        assertThat(constraintColumns("uk_mine_coordinate_system_site_code"))
                .containsExactly("site_id", "system_code");
        assertThat(constraintColumns("uk_mine_coordinate_transform_pair_version"))
                .containsExactly(
                        "source_coordinate_system_id",
                        "target_coordinate_system_id",
                        "version_number");
    }

    @Test
    void 矿区时间绑定必须启用排斥约束生成区间及延迟一致性触发器() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_extension WHERE extname = 'btree_gist'
                """, Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN (
                    'mine_vehicle_device_binding', 'mine_location_tag_binding_claim',
                    'mine_vehicle_tag_binding', 'mine_person_tag_binding',
                    'mine_person_vehicle_binding'
                )
                ORDER BY class.relname
                """, String.class))
                .hasSize(5)
                .allMatch(comment -> comment.matches(".*[\\u4e00-\\u9fff].*"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN (
                    'mine_vehicle_device_binding'::regclass,
                    'mine_location_tag_binding_claim'::regclass,
                    'mine_vehicle_tag_binding'::regclass,
                    'mine_person_tag_binding'::regclass,
                    'mine_person_vehicle_binding'::regclass
                )
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND column_name = 'valid_period'
                  AND is_generated = 'ALWAYS'
                  AND table_name IN (
                    'mine_vehicle_device_binding', 'mine_location_tag_binding_claim',
                    'mine_vehicle_tag_binding', 'mine_person_tag_binding',
                    'mine_person_vehicle_binding'
                  )
                ORDER BY table_name
                """, String.class)).hasSize(5);
        assertThat(jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE contype = 'x'
                  AND conname IN (
                    'ex_mine_vehicle_device_binding_device',
                    'ex_mine_vehicle_device_binding_primary',
                    'ex_mine_location_tag_binding_claim_tag',
                    'ex_mine_vehicle_tag_binding_vehicle',
                    'ex_mine_person_tag_binding_person',
                    'ex_mine_person_vehicle_binding_person',
                    'ex_mine_person_vehicle_binding_primary'
                  )
                ORDER BY conname
                """, String.class)).hasSize(7);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_trigger
                WHERE tgname IN (
                    'ctg_mine_location_tag_claim_consistency',
                    'ctg_mine_vehicle_tag_claim_consistency',
                    'ctg_mine_person_tag_claim_consistency',
                    'ctg_mine_person_tag_site_assignment',
                    'ctg_mine_person_vehicle_site_assignment',
                    'ctg_mine_person_site_assignment_binding_coverage'
                )
                  AND tgdeferrable
                  AND tginitdeferred
                """, Long.class)).isEqualTo(6);
    }

    @Test
    void 资产换绑操作表必须具备中文注释和管理员幂等约束() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT obj_description('mine_binding_switch_operation'::regclass, 'pg_class')
                """, String.class))
                .matches(".*[\\u4e00-\\u9fff].*");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid = 'mine_binding_switch_operation'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(constraintColumns("uk_mine_binding_switch_operation_idempotency"))
                .containsExactly("operator_id", "idempotency_key");
    }

    @Test
    void 定位运行快照表必须注释完整且禁止原地修改() {
        for (String table : List.of(
                "mine_location_runtime_snapshot",
                "mine_location_runtime_snapshot_item")) {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT obj_description(CAST(? AS regclass), 'pg_class')
                    """, String.class, table))
                    .matches(".*[\\u4e00-\\u9fff].*");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_attribute
                    WHERE attrelid = CAST(? AS regclass)
                      AND attnum > 0
                      AND NOT attisdropped
                      AND COALESCE(col_description(attrelid, attnum), '') = ''
                    """, Long.class, table)).isZero();
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_trigger
                WHERE tgrelid IN (
                    'mine_location_runtime_snapshot'::regclass,
                    'mine_location_runtime_snapshot_item'::regclass
                )
                  AND NOT tgisinternal
                  AND tgname LIKE 'trg_mine_location_runtime_snapshot%immutable'
                """, Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname IN (
                    'ck_mine_location_runtime_snapshot_reason',
                    'ck_mine_location_runtime_snapshot_item_type'
                )
                ORDER BY conname
                """, String.class))
                .hasSize(2)
                .anySatisfy(definition -> assertThat(definition).contains("SPATIAL_CHANGE"))
                .anySatisfy(definition -> assertThat(definition).contains(
                        "WAYPOINT_VERSION", "ROUTE_VERSION", "GEOFENCE_VERSION"));
    }

    @Test
    void 人员必须使用组织主档和跨矿区时态任职() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'mine_person'
                  AND column_name IN ('site_id', 'employment_from', 'employment_to')
                """, Long.class)).isZero();
        assertThat(constraintColumns("uk_mine_person_organization_scope"))
                .containsExactly("id", "organization_id");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT obj_description('mine_person_site_assignment'::regclass, 'pg_class')
                """, String.class)).matches(".*[\\u4e00-\\u9fff].*");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid = 'mine_person_site_assignment'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE conname IN (
                    'ex_mine_person_site_assignment_person',
                    'fk_mine_person_tag_binding_person_organization',
                    'fk_mine_person_vehicle_binding_person_organization'
                )
                ORDER BY conname
                """, String.class)).hasSize(3);
    }

    @Test
    void 命令表和所有字段必须有中文注释() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT obj_description('cmd_instance'::regclass, 'pg_class')",
                String.class
        )).isNotBlank();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid = 'cmd_instance'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(constraintColumns("uk_cmd_instance_creator_idempotency"))
                .containsExactly("created_by", "idempotency_key");
    }

    @Test
    void 保留预览凭证表必须完整注释并约束一次性激活语义() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT obj_description('dev_retention_preview_evidence'::regclass, 'pg_class')",
                String.class
        )).matches(".*[\\u4e00-\\u9fff].*");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid = 'dev_retention_preview_evidence'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(constraintColumns("fk_dev_retention_preview_version"))
                .containsExactly("strategy_version_id");
        assertThat(constraintColumns("fk_dev_retention_preview_release"))
                .containsExactly("activated_release_id");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'dev_retention_preview_evidence'
                """, String.class)).contains("idx_dev_retention_preview_expiry");
    }

    @Test
    void 聚合重算请求表必须完整注释并约束幂等与有界范围() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT obj_description('dev_aggregation_recompute_request'::regclass, 'pg_class')",
                String.class
        )).matches(".*[\\u4e00-\\u9fff].*");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid = 'dev_aggregation_recompute_request'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(constraintColumns("uk_dev_aggregation_recompute_request_idempotency"))
                .containsExactly("requested_by", "idempotency_key");
    }

    @Test
    void 聚合重算仓储应持久化幂等请求并稳定返回最近事实() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-02T00:00:00Z");
        try {
            jdbcTemplate.update("""
                    INSERT INTO sys_user (
                        id, username, password_hash, display_name, password_change_required
                    ) VALUES (?, ?, ?, ?, FALSE)
                    """, userId, "aggregation-" + userId, "bcrypt:test", "聚合测试用户");
            jdbcTemplate.update("""
                    INSERT INTO dev_product (id, product_key, name, device_type, status)
                    VALUES (?, ?, '聚合测试产品', 'TEST', 'ACTIVE')
                    """, productId, "aggregation-" + productId.toString().substring(0, 8));
            jdbcTemplate.update("""
                    INSERT INTO dev_device (id, product_id, device_code, name, status)
                    VALUES (?, ?, ?, '聚合测试设备', 'ENABLED')
                    """, deviceId, productId, "aggregation-" + deviceId);
            AggregationRecomputeRepository.RequestRecord record =
                    new AggregationRecomputeRepository.RequestRecord(
                            requestId, deviceId, "engine.rpm", 300, from, to,
                            "补算历史", "repository-it", userId,
                            "PENDING", Instant.now(), null, null, null);

            assertThat(aggregationRecomputeRepository.insert(record)).isTrue();
            assertThat(aggregationRecomputeRepository.insert(record)).isFalse();
            jdbcTemplate.update("""
                    UPDATE dev_aggregation_recompute_request
                       SET created_at = CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                     WHERE request_id = ?
                    """, requestId);
            assertThat(aggregationRecomputeRepository.countUnfinished()).isOne();
            assertThat(aggregationRecomputeRepository.oldestClaimableAgeSeconds())
                    .isGreaterThanOrEqualTo(899L);
            assertThat(aggregationRecomputeRepository.findByIdempotency(userId, "repository-it"))
                    .get()
                    .extracting(AggregationRecomputeRepository.RequestRecord::requestId)
                    .isEqualTo(requestId);
            assertThat(aggregationRecomputeRepository.findRecent(20))
                    .extracting(AggregationRecomputeRepository.RequestRecord::requestId)
                    .contains(requestId);

            UUID firstExecutionId = UUID.randomUUID();
            UUID firstClaimToken = UUID.randomUUID();
            AggregationRecomputeRepository.ClaimRecord firstClaim = aggregationRecomputeRepository
                    .claimNext("telemetry-a", Duration.ofMinutes(5), firstExecutionId, firstClaimToken)
                    .orElseThrow();
            assertThat(firstClaim.requestId()).isEqualTo(requestId);
            assertThat(firstClaim.executionId()).isEqualTo(firstExecutionId);
            assertThat(firstClaim.claimToken()).isEqualTo(firstClaimToken);
            assertThat(firstClaim.attemptCount()).isEqualTo(1);
            assertThat(aggregationRecomputeRepository.oldestClaimableAgeSeconds()).isZero();
            assertThat(aggregationRecomputeRepository.claimNext(
                    "telemetry-b", Duration.ofMinutes(5), UUID.randomUUID(), UUID.randomUUID()))
                    .isEmpty();

            jdbcTemplate.update("""
                    UPDATE dev_aggregation_recompute_request
                       SET claimed_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes',
                           lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                     WHERE request_id = ?
                    """, requestId);
            assertThat(aggregationRecomputeRepository.oldestClaimableAgeSeconds())
                    .isGreaterThanOrEqualTo(899L);
            UUID secondClaimToken = UUID.randomUUID();
            AggregationRecomputeRepository.ClaimRecord recovered = aggregationRecomputeRepository
                    .claimNext("telemetry-b", Duration.ofMinutes(5), UUID.randomUUID(), secondClaimToken)
                    .orElseThrow();
            assertThat(recovered.executionId()).isEqualTo(firstExecutionId);
            assertThat(recovered.claimToken()).isEqualTo(secondClaimToken);
            assertThat(recovered.attemptCount()).isEqualTo(2);
            assertThat(aggregationRecomputeRepository.oldestClaimableAgeSeconds()).isZero();
            assertThat(aggregationRecomputeRepository.complete(
                    requestId, firstExecutionId, firstClaimToken, "SUCCEEDED", null)).isFalse();
            assertThat(aggregationRecomputeRepository.complete(
                    requestId, firstExecutionId, secondClaimToken, "SUCCEEDED", null)).isTrue();
            assertThat(aggregationRecomputeRepository.findByIdempotency(userId, "repository-it"))
                    .get()
                    .extracting(AggregationRecomputeRepository.RequestRecord::status)
                    .isEqualTo("SUCCEEDED");
            assertThat(aggregationRecomputeRepository.countUnfinished()).isZero();
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM dev_aggregation_recompute_request WHERE request_id = ?", requestId);
            jdbcTemplate.update("DELETE FROM dev_device WHERE id = ?", deviceId);
            jdbcTemplate.update("DELETE FROM dev_product WHERE id = ?", productId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
    }

    @Test
    void 保留预览凭证必须在真实数据库中一次消费并支持事务回滚() {
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID strategyVersionId = UUID.randomUUID();
        UUID consumedToken = UUID.randomUUID();
        UUID expiredToken = UUID.randomUUID();
        UUID rollbackToken = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO dev_product (id, product_key, name, device_type, status)
                VALUES (?, ?, '保留凭证测试产品', 'SENSOR', 'ACTIVE')
                """, productId, "retention-evidence-" + productId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO dev_device_group (id, product_id, group_key, name, priority)
                VALUES (?, ?, ?, '保留凭证测试设备组', 800)
                """, groupId, productId, "evidence-" + groupId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO dev_strategy_version (
                    id, product_id, strategy_type, version_number, values_json
                ) VALUES (
                    ?, ?, 'RETENTION', 1,
                    '{"policy":{"enabled":true,"retentionClass":"STANDARD",'
                    '"rawTelemetryDays":90,"qualityEvidenceDays":180}}'::jsonb
                )
                """, strategyVersionId, productId);
        try {
            assertThat(retentionPreviewEvidenceMapper.insert(groupChangeEvidence(
                    consumedToken, strategyVersionId, productId, groupId,
                    now, now.plusSeconds(60)))).isOne();
            assertThat(retentionPreviewEvidenceMapper.markGroupChangeActivated(
                    consumedToken, groupId)).isOne();
            assertThat(retentionPreviewEvidenceMapper.markGroupChangeActivated(
                    consumedToken, groupId)).isZero();

            assertThat(retentionPreviewEvidenceMapper.insert(groupChangeEvidence(
                    expiredToken, strategyVersionId, productId, groupId,
                    now.minusSeconds(120), now.minusSeconds(60)))).isOne();
            assertThat(retentionPreviewEvidenceMapper.markGroupChangeActivated(
                    expiredToken, groupId)).isZero();

            assertThat(retentionPreviewEvidenceMapper.insert(groupChangeEvidence(
                    rollbackToken, strategyVersionId, productId, groupId,
                    now, now.plusSeconds(60)))).isOne();
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                assertThat(retentionPreviewEvidenceMapper.markGroupChangeActivated(
                        rollbackToken, groupId)).isOne();
                throw new IllegalStateException("rollback-retention-token");
            })).isInstanceOf(IllegalStateException.class)
                    .hasMessage("rollback-retention-token");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM dev_retention_preview_evidence
                    WHERE id = ? AND activated_at IS NULL
                    """, Long.class, rollbackToken)).isOne();
            assertThat(retentionPreviewEvidenceMapper.markGroupChangeActivated(
                    rollbackToken, groupId)).isOne();
        } finally {
            jdbcTemplate.update("DELETE FROM dev_retention_preview_evidence WHERE product_id = ?", productId);
            jdbcTemplate.update("DELETE FROM dev_strategy_version WHERE id = ?", strategyVersionId);
            jdbcTemplate.update("DELETE FROM dev_device_group WHERE id = ?", groupId);
            jdbcTemplate.update("DELETE FROM dev_product WHERE id = ?", productId);
        }
    }

    @Test
    void 告警中心表必须具备完整中文注释和处置约束() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN ('alarm_event', 'alarm_acknowledgement')
                ORDER BY class.relname
                """, String.class)).hasSize(2)
                .allMatch(comment -> comment != null && comment.matches(".*[\\u4e00-\\u9fff].*"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN ('alarm_event'::regclass, 'alarm_acknowledgement'::regclass)
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(constraintColumns("uk_alarm_acknowledgement_alarm"))
                .containsExactly("alarm_id");
        assertThat(constraintColumns("uk_alarm_acknowledgement_idempotency"))
                .containsExactly("alarm_id", "idempotency_key");
        assertThat(constraintColumns("fk_alarm_event_device"))
                .containsExactly("device_id");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'alarm_event'
                ORDER BY indexname
                """, String.class)).contains(
                "idx_alarm_event_device_time",
                "idx_alarm_event_rule_time",
                "idx_alarm_event_status_time"
        );
    }

    @Test
    void 通知配置控制面必须只保存密文并具备完整中文注释和发布约束() {
        List<String> tables = List.of(
                "notification_channel",
                "notification_recipient_group",
                "notification_recipient",
                "notification_template",
                "notification_policy",
                "notification_policy_version",
                "notification_policy_step",
                "notification_secret_key_guard"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN (
                    'notification_channel', 'notification_recipient_group',
                    'notification_recipient', 'notification_template',
                    'notification_policy', 'notification_policy_version',
                    'notification_policy_step', 'notification_secret_key_guard'
                )
                ORDER BY class.relname
                """, String.class))
                .hasSize(tables.size())
                .allMatch(comment -> comment != null && comment.matches(".*[\\u4e00-\\u9fff].*"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute attribute
                JOIN pg_class class ON class.oid = attribute.attrelid
                WHERE class.relname IN (
                    'notification_channel', 'notification_recipient_group',
                    'notification_recipient', 'notification_template',
                    'notification_policy', 'notification_policy_version',
                    'notification_policy_step', 'notification_secret_key_guard'
                )
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                  AND COALESCE(col_description(attribute.attrelid, attribute.attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('notification_channel', 'notification_recipient')
                  AND column_name IN ('secret', 'secret_value', 'target', 'target_value')
                """, String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('notification_channel', 'notification_recipient')
                  AND column_name IN ('secret_ciphertext', 'target_ciphertext', 'target_digest')
                ORDER BY column_name
                """, String.class)).containsExactly(
                "secret_ciphertext", "target_ciphertext", "target_digest");
        assertThat(constraintColumns("uk_notification_channel_code"))
                .containsExactly("channel_code");
        assertThat(constraintColumns("uk_notification_recipient_target"))
                .containsExactly("group_id", "channel_type", "target_digest");
        assertThat(constraintColumns("uk_notification_policy_version"))
                .containsExactly("policy_id", "version_number");
        assertThat(constraintColumns("uk_notification_policy_step_order"))
                .containsExactly("policy_version_id", "step_order");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'notification_policy_version'
                """, String.class)).contains("uk_notification_policy_single_published");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'notification_channel'
                  AND column_name = 'secret_ciphertext'
                """, Integer.class)).isEqualTo(65536);
    }

    @Test
    void 内置通知Provider必须由Spring完整注册且渠道Mapper支持真实乐观锁更新() {
        assertThat(notificationProviderRegistry.catalog())
                .extracting(definition -> definition.key().provider())
                .containsExactly(
                        "aliyun-sms",
                        "aliyun-voice",
                        "dingtalk-robot",
                        "feishu-robot",
                        "generic-webhook",
                        "smtp",
                        "wecom-robot"
                );

        UUID channelId = UUID.randomUUID();
        String channelCode = "it-smtp-" + channelId.toString().substring(0, 8);
        NotificationChannel initial = new NotificationChannel(
                channelId,
                channelCode,
                "数据库集成测试邮件渠道",
                NotificationChannelType.EMAIL,
                "smtp",
                "DISABLED",
                "{\"host\":\"smtp.example.com\"}",
                "v1:AAAAAAAAAAAAAAAA:AAAAAAAAAAAAAAAAAAAAAA",
                3000,
                5000,
                null,
                null,
                null,
                0
        );

        try {
            assertThat(notificationChannelMapper.insert(initial, null)).isOne();
            NotificationChannel stored = notificationChannelMapper.findByCode(channelCode)
                    .orElseThrow();
            assertThat(stored.id()).isEqualTo(channelId);
            assertThat(stored.configurationJson()).contains("smtp.example.com");
            assertThat(stored.secretCiphertext()).startsWith("v1:");

            NotificationChannel updated = new NotificationChannel(
                    channelId,
                    channelCode,
                    "数据库集成测试邮件渠道-已更新",
                    NotificationChannelType.EMAIL,
                    "smtp",
                    "DISABLED",
                    "{\"host\":\"mail.example.com\"}",
                    stored.secretCiphertext(),
                    4000,
                    6000,
                    null,
                    stored.createdAt(),
                    stored.updatedAt(),
                    stored.version()
            );
            assertThat(notificationChannelMapper.update(updated, stored.version(), null)).isOne();
            assertThat(notificationChannelMapper.update(updated, stored.version(), null)).isZero();
            assertThat(notificationChannelMapper.findById(channelId).orElseThrow().version())
                    .isEqualTo(stored.version() + 1);
        } finally {
            jdbcTemplate.update("DELETE FROM notification_channel WHERE id = ?", channelId);
        }
    }

    @Test
    void 历史Webhook明文端点必须在启动迁移中移入认证密文() {
        UUID channelId = UUID.randomUUID();
        String channelCode = "it-webhook-" + channelId.toString().substring(0, 8);
        NotificationChannel legacy = new NotificationChannel(
                channelId,
                channelCode,
                "历史 Webhook 迁移测试渠道",
                NotificationChannelType.WEBHOOK,
                "generic-webhook",
                "DISABLED",
                "{\"allowedHosts\":[\"hooks.example.com\"],"
                        + "\"endpointUrl\":\"https://hooks.example.com/secret-token\"}",
                null,
                3000,
                5000,
                null,
                null,
                null,
                0
        );

        try {
            assertThat(notificationChannelMapper.insert(legacy, null)).isOne();

            notificationLegacySecretMigrator.migrate();

            NotificationChannel migrated = notificationChannelMapper.findById(channelId)
                    .orElseThrow();
            assertThat(migrated.configurationJson())
                    .contains("hooks.example.com")
                    .doesNotContain("endpointUrl", "secret-token");
            assertThat(migrated.version()).isOne();
            assertThat(migrated.secretCiphertext())
                    .startsWith("v1:")
                    .doesNotContain("secret-token");
            assertThat(notificationSecretCipher.decrypt(
                    channelId,
                    NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                    migrated.version(),
                    migrated.secretCiphertext()
            )).contains("endpointUrl", "https://hooks.example.com/secret-token");
        } finally {
            jdbcTemplate.update("DELETE FROM notification_channel WHERE id = ?", channelId);
        }
    }

    @Test
    void 告警触发事件和证据必须成对存在或同时为空() {
        UUID productId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID recoveryFirstAlarmId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dev_product (id, product_key, name, device_type)
                VALUES (?, 'alarm-constraint-product', '告警约束测试产品', 'SENSOR')
                """, productId);
        jdbcTemplate.update("""
                INSERT INTO dev_device (id, product_id, device_code, name)
                VALUES (?, ?, 'alarm-constraint-device', '告警约束测试设备')
                """, deviceId, productId);

        try {
            assertThat(jdbcTemplate.update("""
                    INSERT INTO alarm_event (
                        id, device_id, product_key, rule_key, rule_name, source_type, severity,
                        property_id, policy_runtime_revision, policy_snapshot_id,
                        policy_content_hash, raised_at
                    ) VALUES (
                        ?, ?, 'alarm-constraint-product', 'quality-rule', '质量规则', 'QUALITY',
                        'WARNING', NULL, 1, ?, ?, CURRENT_TIMESTAMP
                    )
                    """, recoveryFirstAlarmId, deviceId, UUID.randomUUID(),
                    "sha256:" + "a".repeat(64))).isOne();
            assertRejected("""
                    INSERT INTO alarm_event (
                        id, device_id, product_key, rule_key, rule_name, source_type, severity,
                        property_id, policy_runtime_revision, policy_snapshot_id,
                        policy_content_hash, raised_at, raised_event_id
                    ) VALUES (
                        ?, ?, 'alarm-constraint-product', 'numeric-rule', '数值规则', 'NUMERIC',
                        'CRITICAL', 'temperature', 1, ?, ?, CURRENT_TIMESTAMP, ?
                    )
                    """, UUID.randomUUID(), deviceId, UUID.randomUUID(),
                    "sha256:" + "b".repeat(64), UUID.randomUUID());
            assertRejected("""
                    INSERT INTO alarm_event (
                        id, device_id, product_key, rule_key, rule_name, source_type, severity,
                        property_id, policy_runtime_revision, policy_snapshot_id,
                        policy_content_hash, raised_at, raised_evidence
                    ) VALUES (
                        ?, ?, 'alarm-constraint-product', 'numeric-rule', '数值规则', 'NUMERIC',
                        'CRITICAL', 'temperature', 1, ?, ?, CURRENT_TIMESTAMP, '{}'::jsonb
                    )
                    """, UUID.randomUUID(), deviceId, UUID.randomUUID(),
                    "sha256:" + "c".repeat(64));
        } finally {
            jdbcTemplate.update("DELETE FROM alarm_event WHERE device_id = ?", deviceId);
            jdbcTemplate.update("DELETE FROM dev_device WHERE id = ?", deviceId);
            jdbcTemplate.update("DELETE FROM dev_product WHERE id = ?", productId);
        }
    }

    @Test
    void 应发布只读的系统与设备授权菜单契约() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT component_key
                FROM sys_menu
                WHERE component_key IS NOT NULL
                ORDER BY component_key
                """, String.class)).containsExactly(
                "aggregation-governance",
                "alarm-center",
                "command-center",
                "device-device-list",
                "device-model-management",
                "device-monitor",
                "device-product-list",
                "device-strategy-workbench",
                "mine-binding-management",
                "mine-location-monitor",
                "mine-location-tag-management",
                "mine-person-management",
                "mine-production-operations",
                "mine-scene-workbench",
                "mine-site-archive",
                "mine-spatial-management",
                "mine-vehicle-management",
                "retention-governance",
                "system-dictionary-list",
                "system-menu-list",
                "system-operations-monitor",
                "system-role-list",
                "system-user-list"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT permission
                FROM sys_menu
                WHERE permission LIKE 'system:%'
                ORDER BY permission
                """, String.class)).containsExactly(
                "system:dictionary:create",
                "system:dictionary:delete",
                "system:dictionary:update",
                "system:dictionary:view",
                "system:menu:create",
                "system:menu:delete",
                "system:menu:update",
                "system:menu:view",
                "system:operations:view",
                "system:role:assign-menu",
                "system:role:create",
                "system:role:delete",
                "system:role:update",
                "system:role:view",
                "system:user:assign-role",
                "system:user:create",
                "system:user:reset-password",
                "system:user:update",
                "system:user:view"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT permission
                FROM sys_menu
                WHERE permission LIKE 'device:%'
                ORDER BY permission
                """, String.class)).containsExactly(
                "device:configuration:publish",
                "device:configuration:rollback",
                "device:configuration:view",
                "device:connection:test",
                "device:connection:update",
                "device:connection:view",
                "device:credential:create",
                "device:credential:revoke",
                "device:credential:rotate",
                "device:device:archive",
                "device:device:create",
                "device:device:update",
                "device:device:view",
                "device:group:edit",
                "device:group:view",
                "device:model:edit",
                "device:model:publish",
                "device:model:view",
                "device:product:archive",
                "device:product:create",
                "device:product:update",
                "device:product:view",
                "device:protocol:sample:edit",
                "device:protocol:sample:view",
                "device:protocol:test",
                "device:strategy:edit",
                "device:strategy:publish",
                "device:strategy:view",
                "device:telemetry:view"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT permission
                FROM sys_menu
                WHERE permission LIKE 'command:%'
                ORDER BY permission
                """, String.class)).containsExactly(
                "command:instance:cancel",
                "command:instance:create",
                "command:instance:view",
                "command:template:view"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT permission
                FROM sys_menu
                WHERE permission LIKE 'alarm:%'
                ORDER BY permission
                """, String.class)).containsExactly(
                "alarm:event:ack",
                "alarm:event:close",
                "alarm:event:view"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT permission
                FROM sys_menu
                WHERE permission LIKE 'notification:%'
                ORDER BY permission
                """, String.class)).containsExactly(
                "notification:channel:edit",
                "notification:channel:view"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT permission
                FROM sys_menu
                WHERE permission LIKE 'governance:%'
                ORDER BY permission
                """, String.class)).containsExactly(
                "governance:aggregation:execute",
                "governance:aggregation:view",
                "governance:retention:view"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT permission
                FROM sys_menu
                WHERE permission LIKE 'mine:%'
                ORDER BY permission
                """, String.class)).containsExactly(
                "mine:alarm:view",
                "mine:binding:switch",
                "mine:coordinate:edit",
                "mine:coordinate:publish",
                "mine:coordinate:view",
                "mine:location-tag:edit",
                "mine:location-tag:view",
                "mine:location:view",
                "mine:operation:correct",
                "mine:operation:view",
                "mine:organization:edit",
                "mine:organization:view",
                "mine:person:edit",
                "mine:person:qualification:edit",
                "mine:person:sensitive:edit",
                "mine:person:sensitive:view",
                "mine:person:view",
                "mine:scene:asset:upload",
                "mine:scene:edit",
                "mine:scene:publish",
                "mine:scene:view",
                "mine:scope:assign",
                "mine:shift:close",
                "mine:shift:edit",
                "mine:site:edit",
                "mine:site:view",
                "mine:spatial:edit",
                "mine:spatial:event:view",
                "mine:spatial:publish",
                "mine:spatial:replay",
                "mine:spatial:view",
                "mine:vehicle:edit",
                "mine:vehicle:view",
                "mine:work-order:dispatch",
                "mine:work-order:edit"
        );
        assertThat(jdbcTemplate.queryForMap("""
                SELECT id, name, path, sort_order, visible, i18n_key, protected_node
                FROM sys_menu
                WHERE id = '019b0000-0000-7000-8000-000000000600'
                """)).containsEntry("name", "矿区管理")
                .containsEntry("path", "/mine")
                .containsEntry("sort_order", 125)
                .containsEntry("visible", true)
                .containsEntry("i18n_key", "mine.menu.mine")
                .containsEntry("protected_node", true);
        assertThat(jdbcTemplate.queryForList("""
                SELECT component_key
                FROM sys_menu
                WHERE parent_id = '019b0000-0000-7000-8000-000000000600'
                ORDER BY sort_order
                """, String.class)).containsExactly(
                "mine-site-archive",
                "mine-vehicle-management",
                "mine-person-management",
                "mine-location-tag-management",
                "mine-binding-management",
                "mine-location-monitor",
                "mine-spatial-management",
                "mine-production-operations",
                "mine-scene-workbench"
        );
        assertThat(jdbcTemplate.queryForMap("""
                SELECT name, path, component_key, permission, i18n_key, protected_node
                FROM sys_menu
                WHERE component_key = 'mine-spatial-management'
                """)).containsEntry("name", "路线与围栏")
                .containsEntry("path", "/mine/spatial")
                .containsEntry("permission", "mine:spatial:view")
                .containsEntry("i18n_key", "mine.menu.mineSpatial")
                .containsEntry("protected_node", true);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT name, path, component_key, permission, i18n_key, protected_node
                FROM sys_menu
                WHERE component_key = 'mine-production-operations'
                """)).containsEntry("name", "生产运营")
                .containsEntry("path", "/mine/operations")
                .containsEntry("permission", "mine:operation:view")
                .containsEntry("i18n_key", "mine.menu.mineOperations")
                .containsEntry("protected_node", true);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT name, path, component_key, permission, i18n_key, protected_node
                FROM sys_menu
                WHERE component_key = 'mine-scene-workbench'
                """)).containsEntry("name", "三维场景")
                .containsEntry("path", "/mine/scene")
                .containsEntry("permission", "mine:scene:view")
                .containsEntry("i18n_key", "mine.menu.mineScene")
                .containsEntry("protected_node", true);
        assertThat(jdbcTemplate.queryForList("""
                SELECT permission
                FROM sys_menu
                WHERE parent_id = (
                    SELECT id FROM sys_menu WHERE component_key = 'mine-scene-workbench'
                )
                ORDER BY sort_order
                """, String.class)).containsExactly(
                "mine:scene:edit", "mine:scene:asset:upload", "mine:scene:publish", "mine:alarm:view");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu
                WHERE parent_id = (
                    SELECT id FROM sys_menu WHERE component_key = 'mine-scene-workbench'
                )
                  AND (type <> 'BUTTON' OR path IS NOT NULL OR component_key IS NOT NULL)
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu child
                JOIN sys_menu parent ON parent.id = child.parent_id
                WHERE parent.name = '系统管理'
                  AND parent.type = 'DIRECTORY'
                  AND child.type = 'MENU'
                """, Long.class)).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT parent.component_key AS parent_component_key,
                       strategy.path,
                       strategy.permission,
                       strategy.visible,
                       strategy.i18n_key
                FROM sys_menu strategy
                JOIN sys_menu parent ON parent.id = strategy.parent_id
                WHERE strategy.component_key = 'device-strategy-workbench'
                """)).containsEntry("parent_component_key", null)
                .containsEntry("path", "/device/strategies")
                .containsEntry("permission", "device:strategy:view")
                .containsEntry("visible", true)
                .containsEntry("i18n_key", "mine.menu.strategies");
        assertThat(jdbcTemplate.queryForList("""
                SELECT permission
                FROM sys_menu
                WHERE parent_id = (
                    SELECT id FROM sys_menu WHERE component_key = 'device-strategy-workbench'
                )
                ORDER BY permission
                """, String.class)).containsExactly(
                "device:group:edit",
                "device:group:view",
                "device:strategy:edit",
                "device:strategy:publish"
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu
                WHERE visible = TRUE
                  AND path IN ('/device', '/device/products', '/device/devices', '/device/monitor')
                """, Long.class)).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu
                WHERE visible = TRUE
                  AND path IN ('/command', '/command/center')
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT parent.name AS directory_name,
                       parent.path AS directory_path,
                       parent.i18n_key AS directory_i18n_key,
                       retention.path,
                       retention.permission,
                       retention.visible,
                       retention.i18n_key
                FROM sys_menu retention
                JOIN sys_menu parent ON parent.id = retention.parent_id
                WHERE retention.component_key = 'retention-governance'
                """)).containsEntry("directory_name", "数据治理")
                .containsEntry("directory_path", "/governance")
                .containsEntry("directory_i18n_key", "mine.menu.governance")
                .containsEntry("path", "/governance/retention")
                .containsEntry("permission", "governance:retention:view")
                .containsEntry("visible", true)
                .containsEntry("i18n_key", "mine.menu.retention");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT parent.name AS directory_name,
                       aggregation.path,
                       aggregation.permission,
                       aggregation.visible,
                       aggregation.i18n_key
                FROM sys_menu aggregation
                JOIN sys_menu parent ON parent.id = aggregation.parent_id
                WHERE aggregation.component_key = 'aggregation-governance'
                """)).containsEntry("directory_name", "数据治理")
                .containsEntry("path", "/governance/aggregation")
                .containsEntry("permission", "governance:aggregation:view")
                .containsEntry("visible", true)
                .containsEntry("i18n_key", "mine.menu.aggregation");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu
                WHERE visible = TRUE
                  AND path IN ('/alarm', '/alarm/center')
                """, Long.class)).isEqualTo(2L);
    }

    @Test
    void 菜单扩展和字典表必须具备约束索引及中文注释() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'sys_menu'
                  AND column_name IN ('i18n_key', 'protected_node')
                ORDER BY column_name
                """, String.class)).containsExactly("i18n_key", "protected_node");
        assertThat(constraintColumns("uk_sys_dict_type_code")).containsExactly("code");
        assertThat(constraintColumns("uk_sys_dict_item_type_value")).containsExactly("type_id", "value");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN ('sys_dict_type'::regclass, 'sys_dict_item'::regclass)
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT obj_description('sys_dict_type'::regclass, 'pg_class')",
                String.class
        )).containsPattern("[\\u4e00-\\u9fff]");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT obj_description('sys_dict_item'::regclass, 'pg_class')",
                String.class
        )).containsPattern("[\\u4e00-\\u9fff]");
        assertThat(jdbcTemplate.queryForList("""
                SELECT component_key
                FROM sys_menu
                WHERE path IN ('/system/menus', '/system/dictionaries')
                ORDER BY path
                """, String.class)).containsExactly("system-dictionary-list", "system-menu-list");
    }

    @Test
    void 应预置设备类型与产品设备状态字典() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT code
                FROM sys_dict_type
                WHERE code IN ('device_type', 'product_status', 'device_status')
                  AND status = 'ENABLED'
                  AND built_in = TRUE
                ORDER BY code
                """, String.class)).containsExactly("device_status", "device_type", "product_status");
        assertThat(dictionaryValues("device_type"))
                .containsExactly("VEHICLE", "CONVEYOR", "PUMP", "FAN", "ELECTRICAL", "SENSOR", "OTHER");
        assertThat(dictionaryValues("product_status"))
                .containsExactly("DRAFT", "ACTIVE", "ARCHIVED");
        assertThat(dictionaryValues("device_status"))
                .containsExactly("ENABLED", "DISABLED", "ARCHIVED");
    }

    @Test
    void 物模型和协议映射表必须版本化并具有完整中文注释() {
        assertThat(constraintColumns("uk_dev_model_version_product_number"))
                .containsExactly("product_id", "version_number");
        assertThat(constraintColumns("uk_dev_model_field_identifier"))
                .containsExactly("model_version_id", "identifier");
        assertThat(constraintColumns("uk_dev_protocol_mapping_product_number"))
                .containsExactly("product_id", "profile_key", "version_number");
        assertThat(constraintColumns("uk_dev_protocol_field_source"))
                .containsExactly("mapping_version_id", "source_path");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_dev_protocol_mapping_runtime_binding'
                )
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'dev_protocol_mapping_version'
                  AND column_name IN ('parser_type', 'plugin_key', 'plugin_version', 'routing_mode')
                ORDER BY column_name
                """, String.class)).containsExactly(
                "parser_type", "plugin_key", "plugin_version", "routing_mode");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN (
                    'dev_model_version'::regclass,
                    'dev_model_field'::regclass,
                    'dev_protocol_mapping_version'::regclass,
                    'dev_protocol_field_mapping'::regclass
                )
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN (
                    'dev_model_version', 'dev_model_field',
                    'dev_protocol_mapping_version', 'dev_protocol_field_mapping'
                )
                """, String.class)).allMatch(comment -> comment.matches(".*[\\u4e00-\\u9fff].*"));
    }

    private List<String> dictionaryValues(String code) {
        return jdbcTemplate.queryForList("""
                SELECT item.value
                FROM sys_dict_item item
                JOIN sys_dict_type type ON type.id = item.type_id
                WHERE type.code = ?
                ORDER BY item.sort_order, item.id
                """, String.class, code);
    }

    @Test
    void 协议发布控制面必须具备不可变结构和完整中文注释() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (
                    (table_name = 'dev_protocol_mapping_version' AND column_name = 'metadata_paths')
                    OR (table_name = 'dev_protocol_field_mapping' AND column_name = 'enum_mapping')
                  )
                ORDER BY table_name, column_name
                """, String.class)).containsExactly(
                "dev_protocol_field_mapping.enum_mapping",
                "dev_protocol_mapping_version.metadata_paths"
        );
        assertThat(constraintColumns("uk_dev_protocol_sample_version_name"))
                .containsExactly("mapping_version_id", "name");
        assertThat(constraintColumns("uk_dev_config_package_product_sequence"))
                .containsExactly("product_id", "package_sequence");
        assertThat(constraintColumns("uk_dev_config_release_product_sequence"))
                .containsExactly("product_id", "release_sequence");
        assertThat(constraintColumns("pk_dev_config_node_status"))
                .containsExactly("release_id", "node_id");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN (
                    'dev_protocol_sample'::regclass,
                    'dev_protocol_test_run'::regclass,
                    'dev_config_package'::regclass,
                    'dev_config_release'::regclass,
                    'dev_config_release_target'::regclass,
                    'dev_config_node_status'::regclass
                )
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN (
                    'dev_protocol_sample', 'dev_protocol_test_run', 'dev_config_package',
                    'dev_config_release', 'dev_config_release_target', 'dev_config_node_status'
                )
                """, String.class))
                .hasSize(6)
                .allMatch(comment -> comment.matches(".*[\\u4e00-\\u9fff].*"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_trigger
                WHERE tgname IN ('trg_dev_config_package_immutable', 'trg_dev_config_release_immutable')
                  AND NOT tgisinternal
                """, Long.class)).isEqualTo(2L);
    }

    @Test
    void 策略作用域控制面必须具备历史约束索引和完整中文注释() {
        assertThat(constraintColumns("uk_dev_device_group_product_key"))
                .containsExactly("product_id", "group_key");
        assertThat(constraintColumns("uk_dev_strategy_version_product_type_number"))
                .containsExactly("product_id", "strategy_type", "version_number");
        assertThat(constraintColumns("uk_dev_strategy_release_product_sequence"))
                .containsExactly("product_id", "release_sequence");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN (
                    'uk_dev_device_group_membership_active',
                    'idx_dev_device_group_membership_device_active',
                    'idx_dev_device_group_membership_group_active',
                    'idx_dev_strategy_release_current_scope'
                  )
                ORDER BY indexname
                """, String.class)).containsExactly(
                "idx_dev_device_group_membership_device_active",
                "idx_dev_device_group_membership_group_active",
                "idx_dev_strategy_release_current_scope",
                "uk_dev_device_group_membership_active"
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN (
                    'dev_device_group'::regclass,
                    'dev_device_group_membership'::regclass,
                    'dev_strategy_version'::regclass,
                    'dev_strategy_release'::regclass
                )
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN (
                    'dev_device_group', 'dev_device_group_membership',
                    'dev_strategy_version', 'dev_strategy_release'
                )
                """, String.class))
                .hasSize(4)
                .allMatch(comment -> comment.matches(".*[\\u4e00-\\u9fff].*"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_trigger
                WHERE tgname IN (
                    'trg_dev_strategy_version_published_immutable',
                    'trg_dev_strategy_release_immutable',
                    'trg_dev_device_group_membership_close_on_device_archive'
                )
                  AND NOT tgisinternal
                """, Long.class)).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname IN (
                    'ck_dev_strategy_version_type',
                    'ck_dev_strategy_release_type'
                )
                ORDER BY conname
                """, String.class))
                .hasSize(2)
                .allMatch(definition -> definition.contains("ONLINE_STATUS")
                        && definition.contains("DATA_QUALITY")
                        && definition.contains("ALARM")
                        && definition.contains("RETENTION")
                        && definition.contains("AGGREGATION"));
    }

    @Test
    void 策略运行快照必须具备单调修订不可变约束和完整中文注释() {
        assertThat(constraintColumns("uk_dev_strategy_runtime_snapshot_revision"))
                .containsExactly("product_id", "strategy_type", "runtime_revision");
        assertThat(constraintColumns("pk_dev_strategy_runtime_snapshot_item"))
                .containsExactly("snapshot_id", "device_id");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute
                WHERE attrelid IN (
                    'dev_strategy_runtime_snapshot'::regclass,
                    'dev_strategy_runtime_snapshot_item'::regclass
                )
                  AND attnum > 0
                  AND NOT attisdropped
                  AND COALESCE(col_description(attrelid, attnum), '') = ''
                """, Long.class)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT obj_description(class.oid, 'pg_class')
                FROM pg_class class
                WHERE class.relname IN (
                    'dev_strategy_runtime_snapshot',
                    'dev_strategy_runtime_snapshot_item'
                )
                """, String.class))
                .hasSize(2)
                .allMatch(comment -> comment.matches(".*[\\u4e00-\\u9fff].*"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_trigger
                WHERE tgname IN (
                    'trg_dev_strategy_runtime_snapshot_immutable',
                    'trg_dev_strategy_runtime_snapshot_item_immutable'
                )
                  AND NOT tgisinternal
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_dev_strategy_runtime_snapshot_reason'
                """, String.class)).contains(
                "PUBLISH", "ROLLBACK", "MEMBERSHIP", "PRIORITY",
                "GROUP_ARCHIVE", "DEVICE_CREATE", "DEVICE_ARCHIVE"
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_dev_strategy_runtime_snapshot_type'
                """, String.class)).contains(
                        "DATA_QUALITY", "ALARM", "RETENTION", "AGGREGATION");
    }

    @Test
    void 应由数据库拒绝重复关联和非法业务状态() {
        UUID firstMenuId = UUID.randomUUID();
        UUID secondMenuId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, name, type, permission)
                VALUES (?, '测试权限', 'BUTTON', 'test:device:view')
                """, firstMenuId);

        assertRejected("""
                INSERT INTO sys_menu (id, name, type, permission)
                VALUES (?, '重复权限', 'BUTTON', 'test:device:view')
                """, secondMenuId);
        assertRejected("""
                INSERT INTO sys_user_role (id, user_id, role_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertRejected("""
                INSERT INTO sys_user (id, username, password_hash, display_name, status)
                VALUES (?, 'invalid-status', 'hash', '非法状态', 'UNKNOWN')
                """, UUID.randomUUID());
        assertRejected("""
                INSERT INTO sys_user (id, username, password_hash, display_name, version)
                VALUES (?, 'invalid-version', 'hash', '非法版本', -1)
                """, UUID.randomUUID());
        assertRejected("""
                INSERT INTO sys_operation_log (
                    id, module, action, trace_id, result, duration_ms
                ) VALUES (?, 'system', 'test', 'trace-test', 'SUCCESS', -1)
                """, UUID.randomUUID());
        assertRejected("""
                INSERT INTO sys_outbox_event (
                    id, aggregate_type, aggregate_id, event_type, schema_version, payload
                ) VALUES (?, 'device', ?, 'device.updated', 1, '[]'::JSONB)
                """, UUID.randomUUID(), UUID.randomUUID());
        assertRejected("""
                INSERT INTO sys_outbox_event (
                    id, aggregate_type, aggregate_id, event_type, schema_version,
                    payload, status
                ) VALUES (?, 'device', ?, 'device.updated', 1, '{}'::JSONB, 'PUBLISHED')
                """, UUID.randomUUID(), UUID.randomUUID());

        UUID inboxEventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_inbox_event (consumer_name, event_id, result)
                VALUES ('mine-server', ?, 'SUCCESS')
                """, inboxEventId);
        assertRejected("""
                INSERT INTO sys_inbox_event (consumer_name, event_id, result)
                VALUES ('mine-server', ?, 'SUCCESS')
                """, inboxEventId);
    }

    @Test
    void 发布中事件必须携带可过期的认领租约() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_outbox_event (
                    id, aggregate_type, aggregate_id, event_type, schema_version, payload
                ) VALUES (?, 'config-release', ?, 'config.released', 1, '{}'::JSONB)
                """, eventId, UUID.randomUUID());

        assertRejected("""
                UPDATE sys_outbox_event
                SET status = 'PUBLISHING'
                WHERE id = ?
                """, eventId);

        assertThat(jdbcTemplate.update("""
                UPDATE sys_outbox_event
                SET status = 'PUBLISHING',
                    locked_by = 'relay-test',
                    locked_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE id = ?
                """, eventId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_outbox_event
                WHERE status = 'PUBLISHING'
                  AND lease_expires_at <= CURRENT_TIMESTAMP
                """, Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'idx_sys_outbox_event_expired_lease'
                """, Long.class)).isOne();
    }

    @Test
    void 重复执行迁移时不应修改数据库结构() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void 从四十四版升级时必须拒绝三类非法运营存量() throws Exception {
        assertV45UpgradeRejected(
                "invalid_supervisor", OperationUpgradeViolation.INVALID_SUPERVISOR,
                "V45 升级失败：存在不满足完整矿区任职的班次负责人");
        assertV45UpgradeRejected(
                "terminal_shift_work", OperationUpgradeViolation.TERMINAL_SHIFT_WORK,
                "V45 升级失败：终态班次存在未终结作业单");
        assertV45UpgradeRejected(
                "terminal_work_runtime", OperationUpgradeViolation.TERMINAL_WORK_RUNTIME,
                "V45 升级失败：终态作业单遗留运输循环运行态");
    }

    @Test
    void 从三十六版升级时必须保留人员并回填矿区任职() throws Exception {
        String schema = "person_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID qualificationId = UUID.randomUUID();
        try {
            Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("36")
                    .load()
                    .migrate();
            try (Connection connection = DriverManager.getConnection(
                    POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("""
                        INSERT INTO mine_organization (id, organization_code, name)
                        VALUES ('%s', 'person-upgrade-group', '人员升级集团')
                        """.formatted(organizationId));
                statement.execute("""
                        INSERT INTO mine_site (
                            id, organization_id, site_code, name, timezone
                        ) VALUES (
                            '%s', '%s', 'person-upgrade-pit', '人员升级矿区', 'Asia/Shanghai'
                        )
                        """.formatted(siteId, organizationId));
                statement.execute("""
                        INSERT INTO mine_person (
                            id, organization_id, site_id, person_code, name, person_type,
                            phone_ciphertext, phone_masked, certificate_ciphertext,
                            certificate_masked, employment_from, employment_to
                        ) VALUES (
                            '%s', '%s', '%s', 'upgrade-driver', '升级司机', 'DRIVER',
                            'v1:bm9uY2U:Y2lwaGVydGV4dA', '138****8000',
                            'v1:bm9uY2U:Y2lwaGVydGV4dA', '410***********1234',
                            DATE '2025-01-01', DATE '2025-01-31'
                        )
                        """.formatted(personId, organizationId, siteId));
                statement.execute("""
                        INSERT INTO mine_driver_qualification (
                            id, organization_id, site_id, person_id, qualification_code,
                            name, allowed_vehicle_types, valid_from, valid_to
                        ) VALUES (
                            '%s', '%s', '%s', '%s', 'upgrade-license', '升级驾驶资质',
                            '["HAUL_TRUCK"]'::JSONB, DATE '2025-01-01', DATE '2028-12-31'
                        )
                        """.formatted(qualificationId, organizationId, siteId, personId));
            }

            Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(
                    POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet result = statement.executeQuery("""
                        SELECT person.id, person.phone_ciphertext,
                               assignment.site_id, assignment.valid_from, assignment.valid_to,
                               qualification.id AS qualification_id
                        FROM mine_person person
                        JOIN mine_person_site_assignment assignment
                          ON assignment.person_id = person.id
                        JOIN mine_driver_qualification qualification
                          ON qualification.person_id = person.id
                        WHERE person.id = '%s'
                        """.formatted(personId))) {
                    assertThat(result.next()).isTrue();
                    assertThat(UUID.fromString(result.getString("id"))).isEqualTo(personId);
                    assertThat(result.getString("phone_ciphertext")).startsWith("v1:");
                    assertThat(UUID.fromString(result.getString("site_id"))).isEqualTo(siteId);
                    assertThat(result.getObject("valid_from", java.time.OffsetDateTime.class).toInstant())
                            .isEqualTo(Instant.parse("2024-12-31T16:00:00Z"));
                    assertThat(result.getObject("valid_to", java.time.OffsetDateTime.class).toInstant())
                            .isEqualTo(Instant.parse("2025-01-31T16:00:00Z"));
                    assertThat(UUID.fromString(result.getString("qualification_id")))
                            .isEqualTo(qualificationId);
                    assertThat(result.next()).isFalse();
                }
                try (ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = '%s'
                          AND table_name = 'mine_person'
                          AND column_name IN ('employment_from', 'employment_to')
                        """.formatted(schema))) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
            }
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void 从三十六版升级时必须拒绝超出任职区间的存量人员绑定() throws Exception {
        String schema = "person_binding_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        try {
            Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("36")
                    .load()
                    .migrate();
            try (Connection connection = DriverManager.getConnection(
                    POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("""
                        INSERT INTO mine_organization (id, organization_code, name)
                        VALUES ('%s', 'invalid-binding-group', '非法绑定升级集团')
                        """.formatted(organizationId));
                statement.execute("""
                        INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                        VALUES ('%s', '%s', 'invalid-binding-pit', '非法绑定升级矿区', 'Asia/Shanghai')
                        """.formatted(siteId, organizationId));
                statement.execute("""
                        INSERT INTO mine_person (
                            id, organization_id, site_id, person_code, name, person_type,
                            phone_ciphertext, phone_masked, certificate_ciphertext,
                            certificate_masked, employment_from, employment_to
                        ) VALUES (
                            '%s', '%s', '%s', 'invalid-binding-driver', '非法绑定司机', 'DRIVER',
                            'v1:bm9uY2U:Y2lwaGVydGV4dA', '138****8000',
                            'v1:bm9uY2U:Y2lwaGVydGV4dA', '410***********1234',
                            DATE '2025-01-01', DATE '2025-01-31'
                        )
                        """.formatted(personId, organizationId, siteId));
                statement.execute("""
                        INSERT INTO mine_vehicle (
                            id, organization_id, site_id, vehicle_code, name, vehicle_type
                        ) VALUES ('%s', '%s', '%s', 'invalid-binding-truck', '非法绑定车辆', 'HAUL_TRUCK')
                        """.formatted(vehicleId, organizationId, siteId));
                statement.execute("""
                        INSERT INTO mine_person_vehicle_binding (
                            id, organization_id, site_id, person_id, vehicle_id,
                            assignment_role, valid_from, valid_to, reason
                        ) VALUES (
                            gen_random_uuid(), '%s', '%s', '%s', '%s', 'PRIMARY_DRIVER',
                            TIMESTAMPTZ '2025-02-01 00:00:00+08',
                            TIMESTAMPTZ '2025-02-02 00:00:00+08', '存量非法绑定'
                        )
                        """.formatted(organizationId, siteId, personId, vehicleId));
            }

            assertThatThrownBy(() -> Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate())
                    .hasStackTraceContaining("既有人员绑定未被矿区任职区间完整覆盖");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void 从二十七版升级时既有策略查看角色应自动获得数据保留导航() throws Exception {
        String schema = "retention_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        UUID roleId = UUID.randomUUID();
        try {
            Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("27")
                    .load()
                    .migrate();
            try (Connection connection = DriverManager.getConnection(
                    POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("""
                        INSERT INTO sys_role (id, code, name)
                        VALUES ('%s', 'RETENTION_UPGRADE_TEST', '保留策略升级测试角色')
                        """.formatted(roleId));
                statement.execute("""
                        INSERT INTO sys_role_menu (id, role_id, menu_id)
                        VALUES ('%s', '%s', '019b0000-0000-7000-8000-00000000024b')
                        """.formatted(UUID.randomUUID(), roleId));
            }

            Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(
                    POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet result = statement.executeQuery("""
                        SELECT role_menu.id, role_menu.menu_id, menu.permission
                        FROM sys_role_menu role_menu
                        JOIN sys_menu menu ON menu.id = role_menu.menu_id
                        WHERE role_menu.role_id = '%s'
                        ORDER BY menu.permission
                        """.formatted(roleId))) {
                    List<String> permissions = new ArrayList<>();
                    while (result.next()) {
                        UUID menuId = UUID.fromString(result.getString("menu_id"));
                        if (!menuId.equals(UUID.fromString("019b0000-0000-7000-8000-00000000024b"))) {
                            assertThat(UUID.fromString(result.getString("id")).version()).isEqualTo(7);
                        }
                        permissions.add(result.getString("permission"));
                    }
                    assertThat(permissions).containsExactly(
                            "device:device:view",
                            "device:product:view",
                            "device:strategy:view",
                            "governance:aggregation:view",
                            "governance:retention:view"
                    );
                }
            }
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void 从现场旧二十九版升级时必须保留预览凭证并补齐激活类型() throws Exception {
        String schema = "retention_v29_upgrade_"
                + UUID.randomUUID().toString().replace("-", "");
        UUID productId = UUID.randomUUID();
        UUID strategyVersionId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        String legacyMigration;
        try (var input = getClass().getResourceAsStream(
                "/db/legacy/V29__bind_retention_preview_evidence.sql")) {
            assertThat(input).isNotNull();
            legacyMigration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("28")
                    .load()
                    .migrate();
            try (Connection connection = DriverManager.getConnection(
                    POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute(legacyMigration);
                statement.execute("""
                        INSERT INTO flyway_schema_history (
                            installed_rank, version, description, type, script,
                            checksum, installed_by, execution_time, success
                        ) VALUES (
                            29, '29', 'bind retention preview evidence', 'SQL',
                            'V29__bind_retention_preview_evidence.sql', -1334207752,
                            CURRENT_USER, 0, TRUE
                        )
                        """);
                statement.execute("""
                        INSERT INTO dev_product (id, product_key, name, device_type, status)
                        VALUES ('%s', 'legacy-retention-product', '旧版保留产品', 'SENSOR', 'ACTIVE')
                        """.formatted(productId));
                statement.execute("""
                        INSERT INTO dev_strategy_version (
                            id, product_id, strategy_type, version_number, values_json
                        ) VALUES (
                            '%s', '%s', 'RETENTION', 1,
                            '{"policy":{"enabled":true,"rawTelemetryDays":90}}'::JSONB
                        )
                        """.formatted(strategyVersionId, productId));
                statement.execute("""
                        INSERT INTO dev_retention_preview_evidence (
                            id, strategy_version_id, product_id, scope_type, scope_id,
                            group_priority, candidate_hash, resolution_hash,
                            observed_at, expires_at
                        ) VALUES (
                            '%s', '%s', '%s', 'PRODUCT', '%s', NULL,
                            'sha256:%s', 'sha256:%s',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '15 minutes'
                        )
                        """.formatted(
                        evidenceId, strategyVersionId, productId, productId,
                        "a".repeat(64), "b".repeat(64)));
            }

            Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(
                    POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet result = statement.executeQuery("""
                        SELECT activation_type, change_type, group_version
                        FROM dev_retention_preview_evidence
                        WHERE id = '%s'
                        """.formatted(evidenceId))) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("activation_type")).isEqualTo("STRATEGY_RELEASE");
                    assertThat(result.getString("change_type")).isNull();
                    assertThat(result.getObject("group_version")).isNull();
                    assertThat(result.next()).isFalse();
                }
                try (ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*)
                        FROM pg_attribute
                        WHERE attrelid = 'dev_retention_preview_evidence'::regclass
                          AND attnum > 0
                          AND NOT attisdropped
                          AND COALESCE(col_description(attrelid, attnum), '') = ''
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
            }
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private List<String> constraintColumns(String constraintName) {
        return jdbcTemplate.queryForList("""
                SELECT key_column_usage.column_name
                FROM information_schema.table_constraints
                JOIN information_schema.key_column_usage
                  ON table_constraints.constraint_catalog = key_column_usage.constraint_catalog
                 AND table_constraints.constraint_schema = key_column_usage.constraint_schema
                 AND table_constraints.constraint_name = key_column_usage.constraint_name
                WHERE table_constraints.constraint_schema = 'public'
                  AND table_constraints.constraint_name = ?
                ORDER BY key_column_usage.ordinal_position
                """, String.class, constraintName);
    }

    private void assertV45UpgradeRejected(
            String suffix, OperationUpgradeViolation violation, String expectedMessage
    ) throws Exception {
        String schema = "op45_" + suffix + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID shiftId = UUID.randomUUID();
        UUID workOrderId = UUID.randomUUID();
        String codeSuffix = suffix.replace('_', '-');
        try {
            Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("44")
                    .load()
                    .migrate();
            try (Connection connection = DriverManager.getConnection(
                    POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("""
                        INSERT INTO sys_user (id, username, password_hash, display_name)
                        VALUES ('%s', 'operation-upgrade-%s', 'bcrypt:test', '运营升级用户')
                        """.formatted(userId, codeSuffix));
                statement.execute("""
                        INSERT INTO mine_organization (id, organization_code, name)
                        VALUES ('%s', 'operation-upgrade-%s', '运营升级组织')
                        """.formatted(organizationId, codeSuffix));
                statement.execute("""
                        INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                        VALUES ('%s', '%s', 'operation-site-%s', '运营升级矿区', 'Asia/Shanghai')
                        """.formatted(siteId, organizationId, codeSuffix));
                statement.execute("""
                        INSERT INTO mine_shift_definition (
                            id, organization_id, site_id, definition_code, name,
                            start_local, end_local, valid_from
                        ) VALUES (
                            '%s', '%s', '%s', 'shift-%s', '运营升级班次',
                            TIME '00:00:00', TIME '12:00:00', DATE '2026-01-01'
                        )
                        """.formatted(definitionId, organizationId, siteId, codeSuffix));
                seedOperationUpgradeViolation(
                        statement, violation, organizationId, siteId, userId,
                        definitionId, shiftId, workOrderId);
            }

            assertThatThrownBy(() -> Flyway.configure()
                    .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate())
                    .hasStackTraceContaining(expectedMessage);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void seedOperationUpgradeViolation(
            Statement statement,
            OperationUpgradeViolation violation,
            UUID organizationId,
            UUID siteId,
            UUID userId,
            UUID definitionId,
            UUID shiftId,
            UUID workOrderId
    ) throws Exception {
        UUID supervisorId = UUID.randomUUID();
        if (violation == OperationUpgradeViolation.INVALID_SUPERVISOR) {
            statement.execute("""
                    INSERT INTO mine_person (
                        id, organization_id, person_code, name, person_type,
                        phone_ciphertext, phone_masked,
                        certificate_ciphertext, certificate_masked
                    ) VALUES (
                        '%s', '%s', 'upgrade-supervisor', '非法负责人', 'SUPERVISOR',
                        'v2:phone:cipher', '138****0000',
                        'v2:certificate:cipher', '证件****0000'
                    )
                    """.formatted(supervisorId, organizationId));
            statement.execute(shiftInsertSql(
                    "PLANNED", supervisorId, shiftId, organizationId,
                    siteId, definitionId, userId));
            return;
        }
        String shiftStatus = violation == OperationUpgradeViolation.TERMINAL_SHIFT_WORK
                ? "CLOSED" : "PLANNED";
        statement.execute(shiftInsertSql(
                shiftStatus, null, shiftId, organizationId,
                siteId, definitionId, userId));
        String workStatus = violation == OperationUpgradeViolation.TERMINAL_WORK_RUNTIME
                ? "CANCELLED" : "DRAFT";
        statement.execute("""
                INSERT INTO mine_work_order (
                    id, organization_id, site_id, shift_instance_id,
                    order_number, title, work_type,
                    planned_start_at, planned_end_at, status,
                    cancelled_at, cancelled_by, terminal_reason
                ) VALUES (
                    '%s', '%s', '%s', '%s', 'upgrade-order-%s', '升级作业单', 'HAULAGE',
                    TIMESTAMPTZ '2026-07-20 00:00:00Z',
                    TIMESTAMPTZ '2026-07-20 12:00:00Z', '%s',
                    %s, %s, %s
                )
                """.formatted(
                workOrderId, organizationId, siteId, shiftId, shortUuid(workOrderId), workStatus,
                "CANCELLED".equals(workStatus) ? "CURRENT_TIMESTAMP" : "NULL",
                "CANCELLED".equals(workStatus) ? "'" + userId + "'" : "NULL",
                "CANCELLED".equals(workStatus) ? "'升级前取消'" : "NULL"));
        if (violation != OperationUpgradeViolation.TERMINAL_WORK_RUNTIME) {
            return;
        }
        UUID vehicleId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        statement.execute("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES ('%s', '%s', '%s', 'upgrade-truck', '升级矿卡', 'HAUL_TRUCK')
                """.formatted(vehicleId, organizationId, siteId));
        statement.execute("""
                INSERT INTO mine_work_order_assignment (
                    id, organization_id, site_id, work_order_id, vehicle_id,
                    valid_from, valid_to, assignment_reason
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s',
                    TIMESTAMPTZ '2026-07-20 00:00:00Z',
                    TIMESTAMPTZ '2026-07-20 12:00:00Z', '升级前分配'
                )
                """.formatted(assignmentId, organizationId, siteId, workOrderId, vehicleId));
        statement.execute("""
                INSERT INTO mine_operation_runtime_state (
                    id, organization_id, site_id, work_order_id, assignment_id
                ) VALUES (gen_random_uuid(), '%s', '%s', '%s', '%s')
                """.formatted(organizationId, siteId, workOrderId, assignmentId));
    }

    private String shiftInsertSql(
            String status,
            UUID supervisorId,
            UUID shiftId,
            UUID organizationId,
            UUID siteId,
            UUID definitionId,
            UUID userId
    ) {
        boolean closed = "CLOSED".equals(status);
        return """
                INSERT INTO mine_shift_instance (
                    id, organization_id, site_id, shift_definition_id,
                    business_date, planned_start_at, planned_end_at,
                    actual_start_at, actual_end_at, supervisor_person_id,
                    definition_snapshot, status,
                    opened_at, opened_by, closed_at, closed_by,
                    created_by, updated_by
                ) VALUES (
                    '%s', '%s', '%s', '%s', DATE '2026-07-20',
                    TIMESTAMPTZ '2026-07-20 00:00:00Z',
                    TIMESTAMPTZ '2026-07-20 12:00:00Z',
                    %s, %s, %s, '{}'::JSONB, '%s', %s, %s, %s, %s, '%s', '%s'
                )
                """.formatted(
                shiftId, organizationId, siteId, definitionId,
                closed ? "CURRENT_TIMESTAMP" : "NULL",
                closed ? "CURRENT_TIMESTAMP" : "NULL",
                supervisorId == null ? "NULL" : "'" + supervisorId + "'",
                status,
                closed ? "CURRENT_TIMESTAMP" : "NULL",
                closed ? "'" + userId + "'" : "NULL",
                closed ? "CURRENT_TIMESTAMP" : "NULL",
                closed ? "'" + userId + "'" : "NULL",
                userId, userId);
    }

    private String shortUuid(UUID value) {
        return value.toString().substring(0, 8);
    }

    private enum OperationUpgradeViolation {
        INVALID_SUPERVISOR,
        TERMINAL_SHIFT_WORK,
        TERMINAL_WORK_RUNTIME
    }

    private RetentionPreviewEvidenceMapper.PreviewEvidence groupChangeEvidence(
            UUID token,
            UUID strategyVersionId,
            UUID productId,
            UUID groupId,
            Instant observedAt,
            Instant expiresAt
    ) {
        return new RetentionPreviewEvidenceMapper.PreviewEvidence(
                token, null, "GROUP_CHANGE", "MEMBERSHIP", 0L,
                strategyVersionId, productId, "GROUP", groupId, 800,
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                observedAt, expiresAt, null, null);
    }

    private void assertRejected(String sql, Object... arguments) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, arguments))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
