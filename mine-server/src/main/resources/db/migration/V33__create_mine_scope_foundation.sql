-- 建立集团组织、矿区和用户数据授权的两级作用域基础。
CREATE FUNCTION mine_is_valid_timezone(candidate TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
STRICT
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM pg_timezone_names
        WHERE name = candidate
    )
$$;

CREATE TABLE mine_organization (
    id                UUID         NOT NULL,
    organization_code VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        UUID,
    archived_at       TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_organization PRIMARY KEY (id),
    CONSTRAINT uk_mine_organization_code UNIQUE (organization_code),
    CONSTRAINT fk_mine_organization_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_organization_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_organization_code
        CHECK (organization_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_organization_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_organization_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mine_organization_lifecycle CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_organization_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_organization_status_name
    ON mine_organization(status, name, id);

CREATE TABLE mine_site (
    id              UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    site_code       VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    timezone        VARCHAR(64)  NOT NULL,
    description     VARCHAR(500),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    archived_at     TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_site PRIMARY KEY (id),
    CONSTRAINT uk_mine_site_organization_code UNIQUE (organization_id, site_code),
    CONSTRAINT uk_mine_site_id_organization UNIQUE (id, organization_id),
    CONSTRAINT fk_mine_site_organization
        FOREIGN KEY (organization_id) REFERENCES mine_organization(id),
    CONSTRAINT fk_mine_site_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_site_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_site_code
        CHECK (site_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_site_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_site_timezone
        CHECK (mine_is_valid_timezone(timezone)),
    CONSTRAINT ck_mine_site_description
        CHECK (description IS NULL OR btrim(description) <> ''),
    CONSTRAINT ck_mine_site_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mine_site_lifecycle CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_site_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_site_organization_status_name
    ON mine_site(organization_id, status, name, id);

CREATE TABLE mine_user_scope_grant (
    id              UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    site_id         UUID,
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    granted_by      UUID,
    CONSTRAINT pk_mine_user_scope_grant PRIMARY KEY (id),
    CONSTRAINT uk_mine_user_scope_grant_scope
        UNIQUE NULLS NOT DISTINCT (user_id, organization_id, site_id),
    CONSTRAINT fk_mine_scope_grant_user
        FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_mine_scope_grant_organization
        FOREIGN KEY (organization_id) REFERENCES mine_organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_mine_scope_grant_site_organization
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_mine_scope_grant_granted_by
        FOREIGN KEY (granted_by) REFERENCES sys_user(id) ON DELETE SET NULL
);

CREATE INDEX idx_mine_scope_grant_user_site
    ON mine_user_scope_grant(user_id, site_id, organization_id);
CREATE INDEX idx_mine_scope_grant_organization_user
    ON mine_user_scope_grant(organization_id, user_id, site_id);

COMMENT ON TABLE mine_organization IS '矿业客户集团或组织档案';
COMMENT ON COLUMN mine_organization.id IS '组织唯一标识';
COMMENT ON COLUMN mine_organization.organization_code IS '组织稳定小写业务编码';
COMMENT ON COLUMN mine_organization.name IS '组织名称';
COMMENT ON COLUMN mine_organization.status IS '组织活动或归档状态';
COMMENT ON COLUMN mine_organization.created_at IS '组织创建时间';
COMMENT ON COLUMN mine_organization.created_by IS '组织创建用户标识';
COMMENT ON COLUMN mine_organization.updated_at IS '组织最近更新时间';
COMMENT ON COLUMN mine_organization.updated_by IS '组织最近更新用户标识';
COMMENT ON COLUMN mine_organization.archived_at IS '组织归档时间';
COMMENT ON COLUMN mine_organization.version IS '组织乐观锁版本号';

COMMENT ON TABLE mine_site IS '组织下属的独立矿区档案';
COMMENT ON COLUMN mine_site.id IS '矿区唯一标识';
COMMENT ON COLUMN mine_site.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_site.site_code IS '组织内唯一的矿区稳定小写业务编码';
COMMENT ON COLUMN mine_site.name IS '矿区名称';
COMMENT ON COLUMN mine_site.timezone IS '矿区使用的 IANA 时区名称';
COMMENT ON COLUMN mine_site.description IS '矿区说明';
COMMENT ON COLUMN mine_site.status IS '矿区活动或归档状态';
COMMENT ON COLUMN mine_site.created_at IS '矿区创建时间';
COMMENT ON COLUMN mine_site.created_by IS '矿区创建用户标识';
COMMENT ON COLUMN mine_site.updated_at IS '矿区最近更新时间';
COMMENT ON COLUMN mine_site.updated_by IS '矿区最近更新用户标识';
COMMENT ON COLUMN mine_site.archived_at IS '矿区归档时间';
COMMENT ON COLUMN mine_site.version IS '矿区乐观锁版本号';

COMMENT ON TABLE mine_user_scope_grant IS '用户可访问组织或矿区的数据范围授权';
COMMENT ON COLUMN mine_user_scope_grant.id IS '数据授权唯一标识';
COMMENT ON COLUMN mine_user_scope_grant.user_id IS '被授权用户标识';
COMMENT ON COLUMN mine_user_scope_grant.organization_id IS '授权所属组织标识';
COMMENT ON COLUMN mine_user_scope_grant.site_id IS '授权矿区标识，为空表示整个组织';
COMMENT ON COLUMN mine_user_scope_grant.granted_at IS '授权时间';
COMMENT ON COLUMN mine_user_scope_grant.granted_by IS '执行授权的用户标识';
