package com.bosco.mine.server.integration;

import com.bosco.mine.server.MineServerApplication;
import org.flywaydb.core.Flyway;
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
            "dev_strategy_runtime_snapshot",
            "dev_strategy_runtime_snapshot_item",
            "sys_login_log",
            "sys_operation_log",
            "sys_outbox_event",
            "sys_inbox_event",
            "cmd_instance"
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
    void 应发布只读的系统与设备授权菜单契约() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT component_key
                FROM sys_menu
                WHERE component_key IS NOT NULL
                ORDER BY component_key
                """, String.class)).containsExactly(
                "command-center",
                "device-device-list",
                "device-model-management",
                "device-monitor",
                "device-product-list",
                "device-strategy-workbench",
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
                        && definition.contains("DATA_QUALITY"));
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

    private void assertRejected(String sql, Object... arguments) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, arguments))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
