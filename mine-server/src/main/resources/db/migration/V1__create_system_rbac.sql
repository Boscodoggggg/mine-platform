-- 五表 RBAC 基线。只创建结构，不预置普通用户、角色或菜单数据。
CREATE TABLE sys_user (
    id                       UUID         NOT NULL,
    username                 VARCHAR(64)  NOT NULL,
    password_hash            VARCHAR(255) NOT NULL,
    display_name             VARCHAR(128) NOT NULL,
    status                   VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
    password_change_required BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at            TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               UUID,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by               UUID,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_sys_user PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username),
    CONSTRAINT ck_sys_user_status CHECK (status IN ('ENABLED', 'DISABLED', 'LOCKED')),
    CONSTRAINT ck_sys_user_version CHECK (version >= 0)
);

CREATE TABLE sys_role (
    id          UUID         NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  UUID,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_sys_role PRIMARY KEY (id),
    CONSTRAINT uk_sys_role_code UNIQUE (code),
    CONSTRAINT ck_sys_role_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_sys_role_version CHECK (version >= 0)
);

CREATE TABLE sys_menu (
    id            UUID         NOT NULL,
    parent_id     UUID,
    name          VARCHAR(128) NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    path          VARCHAR(255),
    component_key VARCHAR(255),
    permission    VARCHAR(128),
    icon          VARCHAR(128),
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    visible       BOOLEAN      NOT NULL DEFAULT TRUE,
    status        VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    UUID,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    UUID,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_sys_menu PRIMARY KEY (id),
    CONSTRAINT fk_sys_menu_parent FOREIGN KEY (parent_id) REFERENCES sys_menu (id),
    CONSTRAINT ck_sys_menu_type CHECK (type IN ('DIRECTORY', 'MENU', 'BUTTON')),
    CONSTRAINT ck_sys_menu_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_sys_menu_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_sys_menu_version CHECK (version >= 0),
    CONSTRAINT ck_sys_menu_permission CHECK (permission IS NULL OR permission <> '')
);

CREATE UNIQUE INDEX uk_sys_menu_permission
    ON sys_menu (permission)
    WHERE permission IS NOT NULL;

CREATE INDEX idx_sys_menu_parent_sort
    ON sys_menu (parent_id, sort_order, id);

CREATE TABLE sys_user_role (
    id         UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    role_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    CONSTRAINT pk_sys_user_role PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
);

CREATE INDEX idx_sys_user_role_role
    ON sys_user_role (role_id, user_id);

CREATE TABLE sys_role_menu (
    id         UUID        NOT NULL,
    role_id    UUID        NOT NULL,
    menu_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    CONSTRAINT pk_sys_role_menu PRIMARY KEY (id),
    CONSTRAINT uk_sys_role_menu UNIQUE (role_id, menu_id),
    CONSTRAINT fk_sys_role_menu_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_role_menu_menu FOREIGN KEY (menu_id) REFERENCES sys_menu (id) ON DELETE CASCADE
);

CREATE INDEX idx_sys_role_menu_menu
    ON sys_role_menu (menu_id, role_id);

COMMENT ON TABLE sys_user IS '系统用户';
COMMENT ON TABLE sys_role IS '系统角色';
COMMENT ON TABLE sys_menu IS '目录、页面和按钮权限节点';
COMMENT ON TABLE sys_user_role IS '用户与角色关联';
COMMENT ON TABLE sys_role_menu IS '角色与权限节点关联';
