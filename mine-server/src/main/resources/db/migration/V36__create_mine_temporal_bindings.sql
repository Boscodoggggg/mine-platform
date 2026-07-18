-- 建立矿区资产按发生时间解析的数据库级半开区间绑定约束。
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE mine_vehicle
    ADD CONSTRAINT uk_mine_vehicle_scope UNIQUE (id, organization_id, site_id);
ALTER TABLE mine_location_tag
    ADD CONSTRAINT uk_mine_location_tag_scope UNIQUE (id, organization_id, site_id);

CREATE TABLE mine_vehicle_device_binding (
    id               UUID        NOT NULL,
    organization_id  UUID        NOT NULL,
    site_id          UUID        NOT NULL,
    vehicle_id       UUID        NOT NULL,
    device_id        UUID        NOT NULL,
    binding_role     VARCHAR(16) NOT NULL,
    valid_from       TIMESTAMPTZ NOT NULL,
    valid_to         TIMESTAMPTZ,
    valid_period     TSTZRANGE   GENERATED ALWAYS AS (
        tstzrange(valid_from, valid_to, '[)')
    ) STORED,
    reason           VARCHAR(500) NOT NULL DEFAULT 'INITIAL_IMPORT',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       UUID,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by       UUID,
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_vehicle_device_binding PRIMARY KEY (id),
    CONSTRAINT fk_mine_vehicle_device_binding_vehicle_scope
        FOREIGN KEY (vehicle_id, organization_id, site_id)
        REFERENCES mine_vehicle(id, organization_id, site_id),
    CONSTRAINT fk_mine_vehicle_device_binding_device
        FOREIGN KEY (device_id) REFERENCES dev_device(id),
    CONSTRAINT fk_mine_vehicle_device_binding_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_vehicle_device_binding_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_vehicle_device_binding_role
        CHECK (binding_role IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ck_mine_vehicle_device_binding_period
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mine_vehicle_device_binding_reason
        CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_mine_vehicle_device_binding_audit
        CHECK (updated_at >= created_at AND version >= 0),
    CONSTRAINT ex_mine_vehicle_device_binding_device
        EXCLUDE USING gist (device_id WITH =, valid_period WITH &&)
        DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT ex_mine_vehicle_device_binding_primary
        EXCLUDE USING gist (vehicle_id WITH =, valid_period WITH &&)
        WHERE (binding_role = 'PRIMARY')
        DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX idx_mine_vehicle_device_binding_vehicle_time
    ON mine_vehicle_device_binding(vehicle_id, valid_from DESC, id);

CREATE TABLE mine_location_tag_binding_claim (
    binding_id      UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    site_id         UUID        NOT NULL,
    binding_type    VARCHAR(16) NOT NULL,
    tag_id          UUID        NOT NULL,
    valid_from      TIMESTAMPTZ NOT NULL,
    valid_to        TIMESTAMPTZ,
    valid_period    TSTZRANGE   GENERATED ALWAYS AS (
        tstzrange(valid_from, valid_to, '[)')
    ) STORED,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_location_tag_binding_claim PRIMARY KEY (binding_id),
    CONSTRAINT fk_mine_location_tag_binding_claim_tag_scope
        FOREIGN KEY (tag_id, organization_id, site_id)
        REFERENCES mine_location_tag(id, organization_id, site_id),
    CONSTRAINT ck_mine_location_tag_binding_claim_type
        CHECK (binding_type IN ('VEHICLE', 'PERSON')),
    CONSTRAINT ck_mine_location_tag_binding_claim_period
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ex_mine_location_tag_binding_claim_tag
        EXCLUDE USING gist (tag_id WITH =, valid_period WITH &&)
        DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX idx_mine_location_tag_binding_claim_site_time
    ON mine_location_tag_binding_claim(site_id, valid_from DESC, binding_id);

CREATE TABLE mine_vehicle_tag_binding (
    id              UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    site_id         UUID         NOT NULL,
    vehicle_id      UUID         NOT NULL,
    tag_id          UUID         NOT NULL,
    valid_from      TIMESTAMPTZ  NOT NULL,
    valid_to        TIMESTAMPTZ,
    valid_period    TSTZRANGE    GENERATED ALWAYS AS (
        tstzrange(valid_from, valid_to, '[)')
    ) STORED,
    reason          VARCHAR(500) NOT NULL DEFAULT 'INITIAL_IMPORT',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_vehicle_tag_binding PRIMARY KEY (id),
    CONSTRAINT fk_mine_vehicle_tag_binding_vehicle_scope
        FOREIGN KEY (vehicle_id, organization_id, site_id)
        REFERENCES mine_vehicle(id, organization_id, site_id),
    CONSTRAINT fk_mine_vehicle_tag_binding_tag_scope
        FOREIGN KEY (tag_id, organization_id, site_id)
        REFERENCES mine_location_tag(id, organization_id, site_id),
    CONSTRAINT fk_mine_vehicle_tag_binding_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_vehicle_tag_binding_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_vehicle_tag_binding_period
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mine_vehicle_tag_binding_reason
        CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_mine_vehicle_tag_binding_audit
        CHECK (updated_at >= created_at AND version >= 0),
    CONSTRAINT ex_mine_vehicle_tag_binding_vehicle
        EXCLUDE USING gist (vehicle_id WITH =, valid_period WITH &&)
        DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX idx_mine_vehicle_tag_binding_vehicle_time
    ON mine_vehicle_tag_binding(vehicle_id, valid_from DESC, id);
CREATE INDEX idx_mine_vehicle_tag_binding_tag_time
    ON mine_vehicle_tag_binding(tag_id, valid_from DESC, id);

CREATE TABLE mine_person_tag_binding (
    id              UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    site_id         UUID         NOT NULL,
    person_id       UUID         NOT NULL,
    tag_id          UUID         NOT NULL,
    valid_from      TIMESTAMPTZ  NOT NULL,
    valid_to        TIMESTAMPTZ,
    valid_period    TSTZRANGE    GENERATED ALWAYS AS (
        tstzrange(valid_from, valid_to, '[)')
    ) STORED,
    reason          VARCHAR(500) NOT NULL DEFAULT 'INITIAL_IMPORT',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_person_tag_binding PRIMARY KEY (id),
    CONSTRAINT fk_mine_person_tag_binding_person_scope
        FOREIGN KEY (person_id, organization_id, site_id)
        REFERENCES mine_person(id, organization_id, site_id),
    CONSTRAINT fk_mine_person_tag_binding_tag_scope
        FOREIGN KEY (tag_id, organization_id, site_id)
        REFERENCES mine_location_tag(id, organization_id, site_id),
    CONSTRAINT fk_mine_person_tag_binding_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_person_tag_binding_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_person_tag_binding_period
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mine_person_tag_binding_reason
        CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_mine_person_tag_binding_audit
        CHECK (updated_at >= created_at AND version >= 0),
    CONSTRAINT ex_mine_person_tag_binding_person
        EXCLUDE USING gist (person_id WITH =, valid_period WITH &&)
        DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX idx_mine_person_tag_binding_person_time
    ON mine_person_tag_binding(person_id, valid_from DESC, id);
CREATE INDEX idx_mine_person_tag_binding_tag_time
    ON mine_person_tag_binding(tag_id, valid_from DESC, id);

CREATE TABLE mine_person_vehicle_binding (
    id              UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    site_id         UUID         NOT NULL,
    person_id       UUID         NOT NULL,
    vehicle_id      UUID         NOT NULL,
    assignment_role VARCHAR(24)  NOT NULL,
    valid_from      TIMESTAMPTZ  NOT NULL,
    valid_to        TIMESTAMPTZ,
    valid_period    TSTZRANGE    GENERATED ALWAYS AS (
        tstzrange(valid_from, valid_to, '[)')
    ) STORED,
    reason          VARCHAR(500) NOT NULL DEFAULT 'INITIAL_IMPORT',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_person_vehicle_binding PRIMARY KEY (id),
    CONSTRAINT fk_mine_person_vehicle_binding_person_scope
        FOREIGN KEY (person_id, organization_id, site_id)
        REFERENCES mine_person(id, organization_id, site_id),
    CONSTRAINT fk_mine_person_vehicle_binding_vehicle_scope
        FOREIGN KEY (vehicle_id, organization_id, site_id)
        REFERENCES mine_vehicle(id, organization_id, site_id),
    CONSTRAINT fk_mine_person_vehicle_binding_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_person_vehicle_binding_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_person_vehicle_binding_role
        CHECK (assignment_role IN ('PRIMARY_DRIVER', 'RELIEF_DRIVER')),
    CONSTRAINT ck_mine_person_vehicle_binding_period
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mine_person_vehicle_binding_reason
        CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_mine_person_vehicle_binding_audit
        CHECK (updated_at >= created_at AND version >= 0),
    CONSTRAINT ex_mine_person_vehicle_binding_person
        EXCLUDE USING gist (person_id WITH =, valid_period WITH &&)
        DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT ex_mine_person_vehicle_binding_primary
        EXCLUDE USING gist (vehicle_id WITH =, valid_period WITH &&)
        WHERE (assignment_role = 'PRIMARY_DRIVER')
        DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX idx_mine_person_vehicle_binding_vehicle_time
    ON mine_person_vehicle_binding(vehicle_id, valid_from DESC, id);

CREATE FUNCTION mine_validate_location_tag_binding(candidate_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    claim_count INTEGER;
    vehicle_count INTEGER;
    person_count INTEGER;
    matching_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO claim_count
      FROM mine_location_tag_binding_claim
     WHERE binding_id = candidate_id;
    SELECT COUNT(*) INTO vehicle_count
      FROM mine_vehicle_tag_binding
     WHERE id = candidate_id;
    SELECT COUNT(*) INTO person_count
      FROM mine_person_tag_binding
     WHERE id = candidate_id;

    IF claim_count = 0 AND vehicle_count = 0 AND person_count = 0 THEN
        RETURN;
    END IF;

    IF claim_count <> 1 OR vehicle_count + person_count <> 1 THEN
        RAISE EXCEPTION
            'location tag claim must match exactly one typed binding: %', candidate_id
            USING ERRCODE = '23514';
    END IF;

    SELECT COUNT(*) INTO matching_count
    FROM (
        SELECT vehicle.id
          FROM mine_location_tag_binding_claim claim
          JOIN mine_vehicle_tag_binding vehicle
            ON vehicle.id = claim.binding_id
           AND claim.binding_type = 'VEHICLE'
           AND vehicle.organization_id = claim.organization_id
           AND vehicle.site_id = claim.site_id
           AND vehicle.tag_id = claim.tag_id
           AND vehicle.valid_from = claim.valid_from
           AND vehicle.valid_to IS NOT DISTINCT FROM claim.valid_to
         WHERE claim.binding_id = candidate_id
        UNION ALL
        SELECT person.id
          FROM mine_location_tag_binding_claim claim
          JOIN mine_person_tag_binding person
            ON person.id = claim.binding_id
           AND claim.binding_type = 'PERSON'
           AND person.organization_id = claim.organization_id
           AND person.site_id = claim.site_id
           AND person.tag_id = claim.tag_id
           AND person.valid_from = claim.valid_from
           AND person.valid_to IS NOT DISTINCT FROM claim.valid_to
         WHERE claim.binding_id = candidate_id
    ) matched;

    IF matching_count <> 1 THEN
        RAISE EXCEPTION
            'location tag claim differs from typed binding: %', candidate_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION mine_check_location_tag_binding_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    old_id UUID;
    new_id UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        old_id := COALESCE(
            (to_jsonb(OLD) ->> 'binding_id')::UUID,
            (to_jsonb(OLD) ->> 'id')::UUID
        );
        PERFORM mine_validate_location_tag_binding(old_id);
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_id := COALESCE(
            (to_jsonb(NEW) ->> 'binding_id')::UUID,
            (to_jsonb(NEW) ->> 'id')::UUID
        );
        IF old_id IS NULL OR new_id IS DISTINCT FROM old_id THEN
            PERFORM mine_validate_location_tag_binding(new_id);
        ELSIF TG_OP = 'UPDATE' THEN
            PERFORM mine_validate_location_tag_binding(new_id);
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER ctg_mine_location_tag_claim_consistency
AFTER INSERT OR UPDATE OR DELETE ON mine_location_tag_binding_claim
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION mine_check_location_tag_binding_consistency();

CREATE CONSTRAINT TRIGGER ctg_mine_vehicle_tag_claim_consistency
AFTER INSERT OR UPDATE OR DELETE ON mine_vehicle_tag_binding
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION mine_check_location_tag_binding_consistency();

CREATE CONSTRAINT TRIGGER ctg_mine_person_tag_claim_consistency
AFTER INSERT OR UPDATE OR DELETE ON mine_person_tag_binding
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION mine_check_location_tag_binding_consistency();

COMMENT ON TABLE mine_vehicle_device_binding IS '车辆与可信接入设备的有效时间绑定历史';
COMMENT ON COLUMN mine_vehicle_device_binding.id IS '车辆设备绑定唯一标识';
COMMENT ON COLUMN mine_vehicle_device_binding.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_vehicle_device_binding.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_vehicle_device_binding.vehicle_id IS '被绑定车辆唯一标识';
COMMENT ON COLUMN mine_vehicle_device_binding.device_id IS '可信接入设备唯一标识';
COMMENT ON COLUMN mine_vehicle_device_binding.binding_role IS '主设备或辅助设备角色';
COMMENT ON COLUMN mine_vehicle_device_binding.valid_from IS '绑定生效时间，包含该时刻';
COMMENT ON COLUMN mine_vehicle_device_binding.valid_to IS '绑定失效时间，不包含该时刻，为空表示无上界';
COMMENT ON COLUMN mine_vehicle_device_binding.valid_period IS '数据库生成的半开有效时间区间';
COMMENT ON COLUMN mine_vehicle_device_binding.reason IS '创建或切换绑定的业务原因';
COMMENT ON COLUMN mine_vehicle_device_binding.created_at IS '绑定记录创建时间';
COMMENT ON COLUMN mine_vehicle_device_binding.created_by IS '绑定记录创建用户标识';
COMMENT ON COLUMN mine_vehicle_device_binding.updated_at IS '绑定记录最近更新时间';
COMMENT ON COLUMN mine_vehicle_device_binding.updated_by IS '绑定记录最近更新用户标识';
COMMENT ON COLUMN mine_vehicle_device_binding.version IS '绑定记录乐观锁版本号';

COMMENT ON TABLE mine_location_tag_binding_claim IS '车辆与人员标签绑定共享的数据库排斥占用台账';
COMMENT ON COLUMN mine_location_tag_binding_claim.binding_id IS '对应真实类型绑定的唯一标识';
COMMENT ON COLUMN mine_location_tag_binding_claim.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_location_tag_binding_claim.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_location_tag_binding_claim.binding_type IS '车辆或人员真实绑定类型';
COMMENT ON COLUMN mine_location_tag_binding_claim.tag_id IS '被占用定位标签唯一标识';
COMMENT ON COLUMN mine_location_tag_binding_claim.valid_from IS '标签占用生效时间，包含该时刻';
COMMENT ON COLUMN mine_location_tag_binding_claim.valid_to IS '标签占用失效时间，不包含该时刻，为空表示无上界';
COMMENT ON COLUMN mine_location_tag_binding_claim.valid_period IS '数据库生成的半开标签占用时间区间';
COMMENT ON COLUMN mine_location_tag_binding_claim.created_at IS '占用台账创建时间';

COMMENT ON TABLE mine_vehicle_tag_binding IS '车辆与定位标签的有效时间绑定历史';
COMMENT ON COLUMN mine_vehicle_tag_binding.id IS '车辆标签绑定唯一标识并对应占用台账标识';
COMMENT ON COLUMN mine_vehicle_tag_binding.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_vehicle_tag_binding.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_vehicle_tag_binding.vehicle_id IS '被定位车辆唯一标识';
COMMENT ON COLUMN mine_vehicle_tag_binding.tag_id IS '车辆使用的定位标签唯一标识';
COMMENT ON COLUMN mine_vehicle_tag_binding.valid_from IS '绑定生效时间，包含该时刻';
COMMENT ON COLUMN mine_vehicle_tag_binding.valid_to IS '绑定失效时间，不包含该时刻，为空表示无上界';
COMMENT ON COLUMN mine_vehicle_tag_binding.valid_period IS '数据库生成的半开有效时间区间';
COMMENT ON COLUMN mine_vehicle_tag_binding.reason IS '创建或切换绑定的业务原因';
COMMENT ON COLUMN mine_vehicle_tag_binding.created_at IS '绑定记录创建时间';
COMMENT ON COLUMN mine_vehicle_tag_binding.created_by IS '绑定记录创建用户标识';
COMMENT ON COLUMN mine_vehicle_tag_binding.updated_at IS '绑定记录最近更新时间';
COMMENT ON COLUMN mine_vehicle_tag_binding.updated_by IS '绑定记录最近更新用户标识';
COMMENT ON COLUMN mine_vehicle_tag_binding.version IS '绑定记录乐观锁版本号';

COMMENT ON TABLE mine_person_tag_binding IS '人员与定位标签的有效时间绑定历史';
COMMENT ON COLUMN mine_person_tag_binding.id IS '人员标签绑定唯一标识并对应占用台账标识';
COMMENT ON COLUMN mine_person_tag_binding.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_person_tag_binding.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_person_tag_binding.person_id IS '被定位人员唯一标识';
COMMENT ON COLUMN mine_person_tag_binding.tag_id IS '人员使用的定位标签唯一标识';
COMMENT ON COLUMN mine_person_tag_binding.valid_from IS '绑定生效时间，包含该时刻';
COMMENT ON COLUMN mine_person_tag_binding.valid_to IS '绑定失效时间，不包含该时刻，为空表示无上界';
COMMENT ON COLUMN mine_person_tag_binding.valid_period IS '数据库生成的半开有效时间区间';
COMMENT ON COLUMN mine_person_tag_binding.reason IS '创建或切换绑定的业务原因';
COMMENT ON COLUMN mine_person_tag_binding.created_at IS '绑定记录创建时间';
COMMENT ON COLUMN mine_person_tag_binding.created_by IS '绑定记录创建用户标识';
COMMENT ON COLUMN mine_person_tag_binding.updated_at IS '绑定记录最近更新时间';
COMMENT ON COLUMN mine_person_tag_binding.updated_by IS '绑定记录最近更新用户标识';
COMMENT ON COLUMN mine_person_tag_binding.version IS '绑定记录乐观锁版本号';

COMMENT ON TABLE mine_person_vehicle_binding IS '司机与车辆的有效时间驾驶关系历史';
COMMENT ON COLUMN mine_person_vehicle_binding.id IS '人员车辆绑定唯一标识';
COMMENT ON COLUMN mine_person_vehicle_binding.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_person_vehicle_binding.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_person_vehicle_binding.person_id IS '驾驶人员唯一标识';
COMMENT ON COLUMN mine_person_vehicle_binding.vehicle_id IS '被驾驶车辆唯一标识';
COMMENT ON COLUMN mine_person_vehicle_binding.assignment_role IS '主司机或替班司机角色';
COMMENT ON COLUMN mine_person_vehicle_binding.valid_from IS '驾驶关系生效时间，包含该时刻';
COMMENT ON COLUMN mine_person_vehicle_binding.valid_to IS '驾驶关系失效时间，不包含该时刻，为空表示无上界';
COMMENT ON COLUMN mine_person_vehicle_binding.valid_period IS '数据库生成的半开有效时间区间';
COMMENT ON COLUMN mine_person_vehicle_binding.reason IS '创建或切换绑定的业务原因';
COMMENT ON COLUMN mine_person_vehicle_binding.created_at IS '绑定记录创建时间';
COMMENT ON COLUMN mine_person_vehicle_binding.created_by IS '绑定记录创建用户标识';
COMMENT ON COLUMN mine_person_vehicle_binding.updated_at IS '绑定记录最近更新时间';
COMMENT ON COLUMN mine_person_vehicle_binding.updated_by IS '绑定记录最近更新用户标识';
COMMENT ON COLUMN mine_person_vehicle_binding.version IS '绑定记录乐观锁版本号';
