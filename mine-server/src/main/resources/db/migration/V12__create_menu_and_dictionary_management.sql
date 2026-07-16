-- 菜单管理补充国际化和核心节点保护；通用字典与设备物模型保持独立边界。
ALTER TABLE sys_menu
    ADD COLUMN i18n_key VARCHAR(255),
    ADD COLUMN protected_node BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sys_menu
    ADD CONSTRAINT ck_sys_menu_i18n_key CHECK (i18n_key IS NULL OR btrim(i18n_key) <> '');

CREATE TABLE sys_dict_type (
    id          UUID         NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
    description VARCHAR(500),
    built_in    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  UUID,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_sys_dict_type PRIMARY KEY (id),
    CONSTRAINT uk_sys_dict_type_code UNIQUE (code),
    CONSTRAINT ck_sys_dict_type_code CHECK (code ~ '^[a-z][a-z0-9_]{2,63}$'),
    CONSTRAINT ck_sys_dict_type_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_sys_dict_type_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_sys_dict_type_version CHECK (version >= 0)
);

CREATE INDEX idx_sys_dict_type_status_name
    ON sys_dict_type (status, name, id);

CREATE TABLE sys_dict_item (
    id            UUID         NOT NULL,
    type_id       UUID         NOT NULL,
    value         VARCHAR(128) NOT NULL,
    label_zh      VARCHAR(128) NOT NULL,
    label_en      VARCHAR(128),
    semantic_type VARCHAR(16)  NOT NULL DEFAULT 'default',
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    default_item  BOOLEAN      NOT NULL DEFAULT FALSE,
    status        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    UUID,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    UUID,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_sys_dict_item PRIMARY KEY (id),
    CONSTRAINT uk_sys_dict_item_type_value UNIQUE (type_id, value),
    CONSTRAINT fk_sys_dict_item_type FOREIGN KEY (type_id) REFERENCES sys_dict_type (id),
    CONSTRAINT ck_sys_dict_item_value CHECK (btrim(value) <> ''),
    CONSTRAINT ck_sys_dict_item_label_zh CHECK (btrim(label_zh) <> ''),
    CONSTRAINT ck_sys_dict_item_label_en CHECK (label_en IS NULL OR btrim(label_en) <> ''),
    CONSTRAINT ck_sys_dict_item_semantic CHECK (
        semantic_type IN ('default', 'primary', 'info', 'success', 'warning', 'error')
    ),
    CONSTRAINT ck_sys_dict_item_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_sys_dict_item_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_sys_dict_item_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_sys_dict_item_default
    ON sys_dict_item (type_id)
    WHERE default_item = TRUE AND status = 'ENABLED';

CREATE INDEX idx_sys_dict_item_type_status_sort
    ON sys_dict_item (type_id, status, sort_order, id);

COMMENT ON COLUMN sys_menu.i18n_key IS '前端导航国际化资源键，可为空并回退到节点名称';
COMMENT ON COLUMN sys_menu.protected_node IS '是否为禁止删除的系统核心节点';

COMMENT ON TABLE sys_dict_type IS '系统通用字典类型';
COMMENT ON COLUMN sys_dict_type.id IS '字典类型 UUIDv7 标识';
COMMENT ON COLUMN sys_dict_type.code IS '稳定字典类型编码，创建后不可修改';
COMMENT ON COLUMN sys_dict_type.name IS '字典类型名称';
COMMENT ON COLUMN sys_dict_type.status IS '字典类型启停状态';
COMMENT ON COLUMN sys_dict_type.description IS '字典类型用途说明';
COMMENT ON COLUMN sys_dict_type.built_in IS '是否为系统内置字典类型';
COMMENT ON COLUMN sys_dict_type.created_at IS '创建时间';
COMMENT ON COLUMN sys_dict_type.created_by IS '创建人标识';
COMMENT ON COLUMN sys_dict_type.updated_at IS '最后更新时间';
COMMENT ON COLUMN sys_dict_type.updated_by IS '最后更新人标识';
COMMENT ON COLUMN sys_dict_type.version IS '乐观锁版本';

COMMENT ON TABLE sys_dict_item IS '系统通用字典数据项';
COMMENT ON COLUMN sys_dict_item.id IS '字典数据项 UUIDv7 标识';
COMMENT ON COLUMN sys_dict_item.type_id IS '所属字典类型标识';
COMMENT ON COLUMN sys_dict_item.value IS '稳定字典值，创建后不可修改';
COMMENT ON COLUMN sys_dict_item.label_zh IS '简体中文展示标签';
COMMENT ON COLUMN sys_dict_item.label_en IS '英文展示标签，可为空';
COMMENT ON COLUMN sys_dict_item.semantic_type IS '主题语义类型，不保存 CSS 或颜色值';
COMMENT ON COLUMN sys_dict_item.sort_order IS '同类型内显示顺序';
COMMENT ON COLUMN sys_dict_item.default_item IS '是否为当前类型默认项';
COMMENT ON COLUMN sys_dict_item.status IS '字典数据项启停状态';
COMMENT ON COLUMN sys_dict_item.created_at IS '创建时间';
COMMENT ON COLUMN sys_dict_item.created_by IS '创建人标识';
COMMENT ON COLUMN sys_dict_item.updated_at IS '最后更新时间';
COMMENT ON COLUMN sys_dict_item.updated_by IS '最后更新人标识';
COMMENT ON COLUMN sys_dict_item.version IS '乐观锁版本';

UPDATE sys_menu
SET protected_node = TRUE
WHERE id = '019b0000-0000-7000-8000-000000000100'
   OR component_key IS NOT NULL;

UPDATE sys_menu SET i18n_key = 'mine.menu.system'
WHERE id = '019b0000-0000-7000-8000-000000000100';
UPDATE sys_menu SET i18n_key = 'mine.menu.users'
WHERE component_key = 'system-user-list';
UPDATE sys_menu SET i18n_key = 'mine.menu.roles'
WHERE component_key = 'system-role-list';
UPDATE sys_menu SET i18n_key = 'mine.menu.operations'
WHERE component_key = 'system-operations-monitor';

-- 菜单和字典页面属于系统核心节点，普通角色不自动获得权限。
INSERT INTO sys_menu (
    id, parent_id, name, type, path, component_key, permission,
    icon, sort_order, visible, i18n_key, protected_node
) VALUES
    ('019b0000-0000-7000-8000-000000000140', '019b0000-0000-7000-8000-000000000100',
     '菜单管理', 'MENU', '/system/menus', 'system-menu-list', 'system:menu:view',
     'mdi:menu-open', 30, TRUE, 'mine.menu.menus', TRUE),
    ('019b0000-0000-7000-8000-000000000141', '019b0000-0000-7000-8000-000000000140',
     '创建菜单', 'BUTTON', NULL, NULL, 'system:menu:create', NULL, 10, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000142', '019b0000-0000-7000-8000-000000000140',
     '修改菜单', 'BUTTON', NULL, NULL, 'system:menu:update', NULL, 20, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000143', '019b0000-0000-7000-8000-000000000140',
     '删除菜单', 'BUTTON', NULL, NULL, 'system:menu:delete', NULL, 30, FALSE, NULL, TRUE),

    ('019b0000-0000-7000-8000-000000000150', '019b0000-0000-7000-8000-000000000100',
     '字典管理', 'MENU', '/system/dictionaries', 'system-dictionary-list', 'system:dictionary:view',
     'mdi:book-open-variant', 40, TRUE, 'mine.menu.dictionaries', TRUE),
    ('019b0000-0000-7000-8000-000000000151', '019b0000-0000-7000-8000-000000000150',
     '创建字典', 'BUTTON', NULL, NULL, 'system:dictionary:create', NULL, 10, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000152', '019b0000-0000-7000-8000-000000000150',
     '修改字典', 'BUTTON', NULL, NULL, 'system:dictionary:update', NULL, 20, FALSE, NULL, TRUE),
    ('019b0000-0000-7000-8000-000000000153', '019b0000-0000-7000-8000-000000000150',
     '删除字典', 'BUTTON', NULL, NULL, 'system:dictionary:delete', NULL, 30, FALSE, NULL, TRUE);

-- 运行监控顺延，保持系统管理菜单顺序稳定。
UPDATE sys_menu
SET sort_order = 50
WHERE component_key = 'system-operations-monitor';
