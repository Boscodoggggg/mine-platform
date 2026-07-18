-- 建立矿区人员档案、司机车型资质以及敏感字段密文存储。
CREATE FUNCTION mine_is_valid_vehicle_type_array(candidate JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT jsonb_typeof(candidate) = 'array'
       AND jsonb_array_length(candidate) BETWEEN 1 AND 8
       AND NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements(candidate) AS element(value)
            WHERE jsonb_typeof(element.value) <> 'string'
               OR element.value #>> '{}' NOT IN (
                    'HAUL_TRUCK', 'EXCAVATOR', 'LOADER', 'DRILL', 'DOZER',
                    'WATER_TRUCK', 'LIGHT_VEHICLE', 'OTHER'
               )
       )
       AND jsonb_array_length(candidate) = (
            SELECT COUNT(DISTINCT element.value)
            FROM jsonb_array_elements(candidate) AS element(value)
       )
$$;

CREATE TABLE mine_person (
    id                     UUID         NOT NULL,
    organization_id        UUID         NOT NULL,
    site_id                UUID         NOT NULL,
    person_code            VARCHAR(64)  NOT NULL,
    name                   VARCHAR(128) NOT NULL,
    person_type            VARCHAR(24)  NOT NULL,
    phone_ciphertext       TEXT         NOT NULL,
    phone_masked           VARCHAR(32)  NOT NULL,
    certificate_ciphertext TEXT         NOT NULL,
    certificate_masked     VARCHAR(64)  NOT NULL,
    employment_from        DATE         NOT NULL,
    employment_to          DATE,
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by             UUID,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by             UUID,
    archived_at            TIMESTAMPTZ,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_person PRIMARY KEY (id),
    CONSTRAINT uk_mine_person_organization_code UNIQUE (organization_id, person_code),
    CONSTRAINT uk_mine_person_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_person_site_organization
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_person_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_person_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_person_code
        CHECK (person_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_person_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_person_type
        CHECK (person_type IN ('DRIVER', 'OPERATOR', 'SUPERVISOR', 'WORKER', 'VISITOR', 'OTHER')),
    CONSTRAINT ck_mine_person_ciphertext CHECK (
        phone_ciphertext ~ '^v1:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'
        AND certificate_ciphertext ~ '^v1:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'
        AND btrim(phone_masked) <> ''
        AND btrim(certificate_masked) <> ''
    ),
    CONSTRAINT ck_mine_person_employment_period
        CHECK (employment_to IS NULL OR employment_to >= employment_from),
    CONSTRAINT ck_mine_person_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mine_person_lifecycle CHECK (
        (status <> 'ARCHIVED' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_person_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_person_site_status_name
    ON mine_person(site_id, status, name, id);

CREATE TABLE mine_driver_qualification (
    id                    UUID         NOT NULL,
    organization_id       UUID         NOT NULL,
    site_id               UUID         NOT NULL,
    person_id             UUID         NOT NULL,
    qualification_code    VARCHAR(64)  NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    allowed_vehicle_types JSONB        NOT NULL,
    valid_from            DATE         NOT NULL,
    valid_to              DATE         NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            UUID,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by            UUID,
    revoked_at            TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_driver_qualification PRIMARY KEY (id),
    CONSTRAINT uk_mine_driver_qualification_person_code UNIQUE (person_id, qualification_code),
    CONSTRAINT fk_mine_driver_qualification_person_scope
        FOREIGN KEY (person_id, organization_id, site_id)
        REFERENCES mine_person(id, organization_id, site_id),
    CONSTRAINT fk_mine_driver_qualification_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_driver_qualification_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_driver_qualification_code
        CHECK (qualification_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_driver_qualification_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_driver_qualification_vehicle_types
        CHECK (mine_is_valid_vehicle_type_array(allowed_vehicle_types)),
    CONSTRAINT ck_mine_driver_qualification_period
        CHECK (valid_to >= valid_from),
    CONSTRAINT ck_mine_driver_qualification_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_mine_driver_qualification_lifecycle CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= created_at)
    ),
    CONSTRAINT ck_mine_driver_qualification_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_driver_qualification_person_status_validity
    ON mine_driver_qualification(person_id, status, valid_from, valid_to, id);
CREATE INDEX idx_mine_driver_qualification_vehicle_types
    ON mine_driver_qualification USING GIN (allowed_vehicle_types);

COMMENT ON TABLE mine_person IS '矿区人员档案及加密敏感身份信息';
COMMENT ON COLUMN mine_person.id IS '人员唯一标识';
COMMENT ON COLUMN mine_person.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_person.site_id IS '当前所属矿区唯一标识';
COMMENT ON COLUMN mine_person.person_code IS '组织内唯一的人员稳定小写业务编码';
COMMENT ON COLUMN mine_person.name IS '人员姓名';
COMMENT ON COLUMN mine_person.person_type IS '人员岗位类型';
COMMENT ON COLUMN mine_person.phone_ciphertext IS '带版本和认证标签的手机号密文';
COMMENT ON COLUMN mine_person.phone_masked IS '供常规查询使用的脱敏手机号';
COMMENT ON COLUMN mine_person.certificate_ciphertext IS '带版本和认证标签的证件号码密文';
COMMENT ON COLUMN mine_person.certificate_masked IS '供常规查询使用的脱敏证件号码';
COMMENT ON COLUMN mine_person.employment_from IS '任职开始日期';
COMMENT ON COLUMN mine_person.employment_to IS '任职结束日期，为空表示尚未结束';
COMMENT ON COLUMN mine_person.status IS '人员在职、停用或归档状态';
COMMENT ON COLUMN mine_person.created_at IS '人员档案创建时间';
COMMENT ON COLUMN mine_person.created_by IS '人员档案创建用户标识';
COMMENT ON COLUMN mine_person.updated_at IS '人员档案最近更新时间';
COMMENT ON COLUMN mine_person.updated_by IS '人员档案最近更新用户标识';
COMMENT ON COLUMN mine_person.archived_at IS '人员档案归档时间';
COMMENT ON COLUMN mine_person.version IS '人员档案乐观锁版本号';

COMMENT ON TABLE mine_driver_qualification IS '司机可驾驶车型及有效期限资质';
COMMENT ON COLUMN mine_driver_qualification.id IS '司机资质唯一标识';
COMMENT ON COLUMN mine_driver_qualification.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_driver_qualification.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_driver_qualification.person_id IS '持有资质的人员唯一标识';
COMMENT ON COLUMN mine_driver_qualification.qualification_code IS '人员内唯一的资质稳定小写业务编码';
COMMENT ON COLUMN mine_driver_qualification.name IS '资质名称';
COMMENT ON COLUMN mine_driver_qualification.allowed_vehicle_types IS '允许驾驶的车辆类型 JSON 数组';
COMMENT ON COLUMN mine_driver_qualification.valid_from IS '资质有效开始日期';
COMMENT ON COLUMN mine_driver_qualification.valid_to IS '资质有效结束日期';
COMMENT ON COLUMN mine_driver_qualification.status IS '资质有效或吊销状态';
COMMENT ON COLUMN mine_driver_qualification.created_at IS '资质创建时间';
COMMENT ON COLUMN mine_driver_qualification.created_by IS '资质创建用户标识';
COMMENT ON COLUMN mine_driver_qualification.updated_at IS '资质最近更新时间';
COMMENT ON COLUMN mine_driver_qualification.updated_by IS '资质最近更新用户标识';
COMMENT ON COLUMN mine_driver_qualification.revoked_at IS '资质吊销时间';
COMMENT ON COLUMN mine_driver_qualification.version IS '资质乐观锁版本号';
