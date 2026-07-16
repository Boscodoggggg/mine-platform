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
                "device:credential:create",
                "device:credential:revoke",
                "device:credential:rotate",
                "device:device:archive",
                "device:device:create",
                "device:device:update",
                "device:device:view",
                "device:model:edit",
                "device:model:publish",
                "device:model:view",
                "device:product:archive",
                "device:product:create",
                "device:product:update",
                "device:product:view",
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
