-- 建立矿区车辆、定位标签以及可版本化发布的双坐标模型。
CREATE TABLE mine_vehicle (
    id                UUID          NOT NULL,
    organization_id   UUID          NOT NULL,
    site_id           UUID          NOT NULL,
    vehicle_code      VARCHAR(64)   NOT NULL,
    name              VARCHAR(128)  NOT NULL,
    vehicle_type      VARCHAR(32)   NOT NULL,
    mine_number       VARCHAR(64),
    license_plate     VARCHAR(32),
    brand             VARCHAR(64),
    model             VARCHAR(64),
    rated_load_tons   NUMERIC(12,3),
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        UUID,
    archived_at       TIMESTAMPTZ,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_vehicle PRIMARY KEY (id),
    CONSTRAINT uk_mine_vehicle_site_code UNIQUE (site_id, vehicle_code),
    CONSTRAINT fk_mine_vehicle_site_organization
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_vehicle_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_vehicle_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_vehicle_code
        CHECK (vehicle_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_vehicle_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_vehicle_type CHECK (
        vehicle_type IN (
            'HAUL_TRUCK', 'EXCAVATOR', 'LOADER', 'DRILL', 'DOZER',
            'WATER_TRUCK', 'LIGHT_VEHICLE', 'OTHER'
        )
    ),
    CONSTRAINT ck_mine_vehicle_optional_text CHECK (
        (mine_number IS NULL OR btrim(mine_number) <> '')
        AND (license_plate IS NULL OR btrim(license_plate) <> '')
        AND (brand IS NULL OR btrim(brand) <> '')
        AND (model IS NULL OR btrim(model) <> '')
    ),
    CONSTRAINT ck_mine_vehicle_rated_load
        CHECK (rated_load_tons IS NULL OR rated_load_tons > 0),
    CONSTRAINT ck_mine_vehicle_status
        CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'OUT_OF_SERVICE', 'ARCHIVED')),
    CONSTRAINT ck_mine_vehicle_lifecycle CHECK (
        (status <> 'ARCHIVED' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_vehicle_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_vehicle_site_status_name
    ON mine_vehicle(site_id, status, name, id);
CREATE UNIQUE INDEX uk_mine_vehicle_site_mine_number
    ON mine_vehicle(site_id, mine_number)
    WHERE mine_number IS NOT NULL AND status <> 'ARCHIVED';

CREATE TABLE mine_location_tag (
    id                  UUID         NOT NULL,
    organization_id     UUID         NOT NULL,
    site_id             UUID         NOT NULL,
    tag_code            VARCHAR(64)  NOT NULL,
    vendor              VARCHAR(128),
    model               VARCHAR(64),
    battery_percent     SMALLINT,
    battery_observed_at TIMESTAMPTZ,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          UUID,
    archived_at         TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_location_tag PRIMARY KEY (id),
    CONSTRAINT uk_mine_location_tag_site_code UNIQUE (site_id, tag_code),
    CONSTRAINT fk_mine_location_tag_site_organization
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_location_tag_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_location_tag_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_location_tag_code
        CHECK (tag_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_location_tag_optional_text CHECK (
        (vendor IS NULL OR btrim(vendor) <> '')
        AND (model IS NULL OR btrim(model) <> '')
    ),
    CONSTRAINT ck_mine_location_tag_battery
        CHECK (battery_percent IS NULL OR battery_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_mine_location_tag_status
        CHECK (status IN ('ACTIVE', 'LOST', 'RETIRED', 'ARCHIVED')),
    CONSTRAINT ck_mine_location_tag_lifecycle CHECK (
        (status <> 'ARCHIVED' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_location_tag_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_location_tag_site_status_code
    ON mine_location_tag(site_id, status, tag_code, id);

CREATE TABLE mine_coordinate_system (
    id                UUID             NOT NULL,
    organization_id   UUID             NOT NULL,
    site_id           UUID             NOT NULL,
    system_code       VARCHAR(64)      NOT NULL,
    name              VARCHAR(128)     NOT NULL,
    coordinate_type   VARCHAR(24)      NOT NULL,
    unit              VARCHAR(16)      NOT NULL,
    epsg_code         INTEGER,
    unified           BOOLEAN          NOT NULL DEFAULT FALSE,
    min_x             DOUBLE PRECISION NOT NULL,
    max_x             DOUBLE PRECISION NOT NULL,
    min_y             DOUBLE PRECISION NOT NULL,
    max_y             DOUBLE PRECISION NOT NULL,
    min_z             DOUBLE PRECISION,
    max_z             DOUBLE PRECISION,
    status            VARCHAR(16)      NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID,
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        UUID,
    archived_at       TIMESTAMPTZ,
    version           BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_coordinate_system PRIMARY KEY (id),
    CONSTRAINT uk_mine_coordinate_system_site_code UNIQUE (site_id, system_code),
    CONSTRAINT uk_mine_coordinate_system_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_coordinate_system_site_organization
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_coordinate_system_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_coordinate_system_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_coordinate_system_code
        CHECK (system_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_coordinate_system_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_coordinate_system_type
        CHECK (coordinate_type IN ('LOCAL_CARTESIAN', 'WGS84', 'ENU')),
    CONSTRAINT ck_mine_coordinate_system_unit
        CHECK (unit IN ('METER', 'MILLIMETER', 'DEGREE')),
    CONSTRAINT ck_mine_coordinate_system_epsg
        CHECK (epsg_code IS NULL OR epsg_code BETWEEN 1 AND 999999),
    CONSTRAINT ck_mine_coordinate_system_bounds CHECK (
        min_x < max_x
        AND min_y < max_y
        AND min_x > '-Infinity'::DOUBLE PRECISION
        AND max_x < 'Infinity'::DOUBLE PRECISION
        AND min_y > '-Infinity'::DOUBLE PRECISION
        AND max_y < 'Infinity'::DOUBLE PRECISION
        AND (
            (min_z IS NULL AND max_z IS NULL)
            OR (
                min_z IS NOT NULL
                AND max_z IS NOT NULL
                AND min_z < max_z
                AND min_z > '-Infinity'::DOUBLE PRECISION
                AND max_z < 'Infinity'::DOUBLE PRECISION
            )
        )
    ),
    CONSTRAINT ck_mine_coordinate_system_semantics CHECK (
        (
            coordinate_type = 'WGS84'
            AND unit = 'DEGREE'
            AND min_x >= -180 AND max_x <= 180
            AND min_y >= -90 AND max_y <= 90
        )
        OR (
            coordinate_type IN ('LOCAL_CARTESIAN', 'ENU')
            AND unit IN ('METER', 'MILLIMETER')
        )
    ),
    CONSTRAINT ck_mine_coordinate_system_unified
        CHECK (NOT unified OR (coordinate_type = 'LOCAL_CARTESIAN' AND unit = 'METER')),
    CONSTRAINT ck_mine_coordinate_system_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mine_coordinate_system_lifecycle CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_coordinate_system_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_coordinate_system_site_status_name
    ON mine_coordinate_system(site_id, status, name, id);
CREATE UNIQUE INDEX uk_mine_coordinate_system_unified
    ON mine_coordinate_system(site_id)
    WHERE unified AND status = 'ACTIVE';

CREATE FUNCTION mine_is_numeric_json_array(candidate JSONB, expected_length INTEGER)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT CASE
        WHEN jsonb_typeof(candidate) <> 'array' OR expected_length < 0 THEN FALSE
        ELSE jsonb_array_length(candidate) = expected_length
            AND NOT EXISTS (
                SELECT 1
                FROM jsonb_array_elements(candidate) AS element(value)
                WHERE jsonb_typeof(element.value) <> 'number'
            )
    END
$$;

CREATE TABLE mine_coordinate_transform_version (
    id                          UUID         NOT NULL,
    organization_id             UUID         NOT NULL,
    site_id                     UUID         NOT NULL,
    source_coordinate_system_id UUID         NOT NULL,
    target_coordinate_system_id UUID         NOT NULL,
    version_number              INTEGER      NOT NULL,
    algorithm                   VARCHAR(24)  NOT NULL,
    parameters                  JSONB        NOT NULL,
    valid_from                  TIMESTAMPTZ  NOT NULL,
    valid_to                    TIMESTAMPTZ,
    status                      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    content_hash                VARCHAR(71),
    published_at                TIMESTAMPTZ,
    published_by                UUID,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                  UUID,
    CONSTRAINT pk_mine_coordinate_transform_version PRIMARY KEY (id),
    CONSTRAINT uk_mine_coordinate_transform_pair_version UNIQUE (
        source_coordinate_system_id,
        target_coordinate_system_id,
        version_number
    ),
    CONSTRAINT fk_mine_coordinate_transform_source_scope
        FOREIGN KEY (source_coordinate_system_id, organization_id, site_id)
        REFERENCES mine_coordinate_system(id, organization_id, site_id),
    CONSTRAINT fk_mine_coordinate_transform_target_scope
        FOREIGN KEY (target_coordinate_system_id, organization_id, site_id)
        REFERENCES mine_coordinate_system(id, organization_id, site_id),
    CONSTRAINT fk_mine_coordinate_transform_published_by
        FOREIGN KEY (published_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_coordinate_transform_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_coordinate_transform_version
        CHECK (version_number > 0),
    CONSTRAINT ck_mine_coordinate_transform_pair
        CHECK (source_coordinate_system_id <> target_coordinate_system_id),
    CONSTRAINT ck_mine_coordinate_transform_algorithm
        CHECK (algorithm IN ('IDENTITY', 'AFFINE_3D', 'WGS84_ENU')),
    CONSTRAINT ck_mine_coordinate_transform_parameters CHECK (
        COALESCE(
            jsonb_typeof(parameters) = 'object'
            AND CASE algorithm
                WHEN 'IDENTITY' THEN parameters = '{}'::jsonb
                WHEN 'AFFINE_3D' THEN
                    mine_is_numeric_json_array(parameters -> 'matrix', 16)
                WHEN 'WGS84_ENU' THEN
                    CASE WHEN parameters ?& ARRAY[
                        'originLongitude', 'originLatitude', 'originAltitude'
                    ]
                        AND jsonb_typeof(parameters -> 'originLongitude') = 'number'
                        AND jsonb_typeof(parameters -> 'originLatitude') = 'number'
                        AND jsonb_typeof(parameters -> 'originAltitude') = 'number'
                    THEN
                        (parameters ->> 'originLongitude')::DOUBLE PRECISION
                            BETWEEN -180 AND 180
                        AND (parameters ->> 'originLatitude')::DOUBLE PRECISION
                            BETWEEN -90 AND 90
                    ELSE FALSE
                    END
                ELSE FALSE
            END,
            FALSE
        )
    ),
    CONSTRAINT ck_mine_coordinate_transform_validity
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mine_coordinate_transform_status
        CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_mine_coordinate_transform_publication CHECK (
        (
            status = 'DRAFT'
            AND content_hash IS NULL
            AND published_at IS NULL
            AND published_by IS NULL
        )
        OR (
            status = 'PUBLISHED'
            AND content_hash ~ '^sha256:[0-9a-f]{64}$'
            AND published_at IS NOT NULL
            AND published_at >= created_at
        )
    )
);

CREATE INDEX idx_mine_coordinate_transform_site_status_validity
    ON mine_coordinate_transform_version(site_id, status, valid_from DESC, id);
CREATE INDEX idx_mine_coordinate_transform_pair_validity
    ON mine_coordinate_transform_version(
        source_coordinate_system_id,
        target_coordinate_system_id,
        status,
        valid_from DESC
    );

CREATE FUNCTION mine_validate_coordinate_transform_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    latest_version INTEGER;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'mine-coordinate-transform:'
        || NEW.source_coordinate_system_id::TEXT
        || ':'
        || NEW.target_coordinate_system_id::TEXT,
        0
    ));

    IF NEW.status = 'PUBLISHED'
            AND (TG_OP = 'INSERT' OR OLD.status <> 'PUBLISHED') THEN
        IF NEW.source_coordinate_system_id::TEXT < NEW.target_coordinate_system_id::TEXT THEN
            PERFORM pg_advisory_xact_lock(hashtextextended(
                'mine-coordinate-system:' || NEW.source_coordinate_system_id::TEXT,
                0
            ));
            PERFORM pg_advisory_xact_lock(hashtextextended(
                'mine-coordinate-system:' || NEW.target_coordinate_system_id::TEXT,
                0
            ));
        ELSE
            PERFORM pg_advisory_xact_lock(hashtextextended(
                'mine-coordinate-system:' || NEW.target_coordinate_system_id::TEXT,
                0
            ));
            PERFORM pg_advisory_xact_lock(hashtextextended(
                'mine-coordinate-system:' || NEW.source_coordinate_system_id::TEXT,
                0
            ));
        END IF;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT MAX(version_number)
          INTO latest_version
          FROM mine_coordinate_transform_version
         WHERE source_coordinate_system_id = NEW.source_coordinate_system_id
           AND target_coordinate_system_id = NEW.target_coordinate_system_id;
        IF latest_version IS NOT NULL AND NEW.version_number <= latest_version THEN
            RAISE EXCEPTION
                'coordinate transform version must be greater than %, actual %',
                latest_version,
                NEW.version_number
                USING ERRCODE = '23514';
        END IF;
    ELSIF ROW(
        NEW.id,
        NEW.organization_id,
        NEW.site_id,
        NEW.source_coordinate_system_id,
        NEW.target_coordinate_system_id,
        NEW.version_number,
        NEW.created_at,
        NEW.created_by
    ) IS DISTINCT FROM ROW(
        OLD.id,
        OLD.organization_id,
        OLD.site_id,
        OLD.source_coordinate_system_id,
        OLD.target_coordinate_system_id,
        OLD.version_number,
        OLD.created_at,
        OLD.created_by
    ) THEN
        RAISE EXCEPTION 'coordinate transform version identity is immutable: %', OLD.id
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'PUBLISHED'
            AND (TG_OP = 'INSERT' OR OLD.status <> 'PUBLISHED')
            AND EXISTS (
                SELECT 1
                  FROM mine_coordinate_transform_version existing
                 WHERE existing.source_coordinate_system_id = NEW.source_coordinate_system_id
                   AND existing.target_coordinate_system_id = NEW.target_coordinate_system_id
                   AND existing.status = 'PUBLISHED'
                   AND existing.id <> NEW.id
                   AND tstzrange(existing.valid_from, existing.valid_to, '[)')
                       && tstzrange(NEW.valid_from, NEW.valid_to, '[)')
            ) THEN
        RAISE EXCEPTION 'published coordinate transform validity overlaps: %', NEW.id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mine_coordinate_transform_guard
BEFORE INSERT OR UPDATE ON mine_coordinate_transform_version
FOR EACH ROW
EXECUTE FUNCTION mine_validate_coordinate_transform_version();

CREATE FUNCTION mine_protect_published_coordinate_transform()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'published coordinate transform version is immutable: %', OLD.id
            USING ERRCODE = '23514';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_mine_coordinate_transform_immutable
BEFORE UPDATE OR DELETE ON mine_coordinate_transform_version
FOR EACH ROW
EXECUTE FUNCTION mine_protect_published_coordinate_transform();

CREATE FUNCTION mine_protect_referenced_coordinate_system()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        NEW.coordinate_type,
        NEW.unit,
        NEW.epsg_code,
        NEW.unified,
        NEW.min_x,
        NEW.max_x,
        NEW.min_y,
        NEW.max_y,
        NEW.min_z,
        NEW.max_z
    ) IS DISTINCT FROM ROW(
        OLD.coordinate_type,
        OLD.unit,
        OLD.epsg_code,
        OLD.unified,
        OLD.min_x,
        OLD.max_x,
        OLD.min_y,
        OLD.max_y,
        OLD.min_z,
        OLD.max_z
    ) THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'mine-coordinate-system:' || OLD.id::TEXT,
            0
        ));
        IF EXISTS (
            SELECT 1
              FROM mine_coordinate_transform_version transform
             WHERE transform.status = 'PUBLISHED'
               AND (
                   transform.source_coordinate_system_id = OLD.id
                   OR transform.target_coordinate_system_id = OLD.id
               )
        ) THEN
            RAISE EXCEPTION
                'coordinate system semantics are referenced by a published version: %',
                OLD.id
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mine_coordinate_system_semantics_immutable
BEFORE UPDATE ON mine_coordinate_system
FOR EACH ROW
EXECUTE FUNCTION mine_protect_referenced_coordinate_system();

COMMENT ON TABLE mine_vehicle IS '矿区生产与辅助车辆主数据';
COMMENT ON COLUMN mine_vehicle.id IS '车辆唯一标识';
COMMENT ON COLUMN mine_vehicle.organization_id IS '车辆所属组织标识';
COMMENT ON COLUMN mine_vehicle.site_id IS '车辆所属矿区标识';
COMMENT ON COLUMN mine_vehicle.vehicle_code IS '矿区内唯一的车辆稳定小写业务编码';
COMMENT ON COLUMN mine_vehicle.name IS '车辆名称';
COMMENT ON COLUMN mine_vehicle.vehicle_type IS '车辆类型';
COMMENT ON COLUMN mine_vehicle.mine_number IS '矿山内部车辆编号';
COMMENT ON COLUMN mine_vehicle.license_plate IS '车辆牌照号码';
COMMENT ON COLUMN mine_vehicle.brand IS '车辆品牌';
COMMENT ON COLUMN mine_vehicle.model IS '车辆型号';
COMMENT ON COLUMN mine_vehicle.rated_load_tons IS '车辆额定载重吨数';
COMMENT ON COLUMN mine_vehicle.status IS '车辆活动、维护、停用或归档状态';
COMMENT ON COLUMN mine_vehicle.created_at IS '车辆创建时间';
COMMENT ON COLUMN mine_vehicle.created_by IS '车辆创建用户标识';
COMMENT ON COLUMN mine_vehicle.updated_at IS '车辆最近更新时间';
COMMENT ON COLUMN mine_vehicle.updated_by IS '车辆最近更新用户标识';
COMMENT ON COLUMN mine_vehicle.archived_at IS '车辆归档时间';
COMMENT ON COLUMN mine_vehicle.version IS '车辆乐观锁版本号';

COMMENT ON TABLE mine_location_tag IS '矿区人员或车辆使用的定位标签主数据';
COMMENT ON COLUMN mine_location_tag.id IS '定位标签唯一标识';
COMMENT ON COLUMN mine_location_tag.organization_id IS '定位标签所属组织标识';
COMMENT ON COLUMN mine_location_tag.site_id IS '定位标签所属矿区标识';
COMMENT ON COLUMN mine_location_tag.tag_code IS '矿区内唯一的定位标签稳定小写业务编码';
COMMENT ON COLUMN mine_location_tag.vendor IS '定位标签厂商';
COMMENT ON COLUMN mine_location_tag.model IS '定位标签型号';
COMMENT ON COLUMN mine_location_tag.battery_percent IS '最近上报的电池电量百分比';
COMMENT ON COLUMN mine_location_tag.battery_observed_at IS '最近电量观测时间';
COMMENT ON COLUMN mine_location_tag.status IS '定位标签活动、丢失、退役或归档状态';
COMMENT ON COLUMN mine_location_tag.created_at IS '定位标签创建时间';
COMMENT ON COLUMN mine_location_tag.created_by IS '定位标签创建用户标识';
COMMENT ON COLUMN mine_location_tag.updated_at IS '定位标签最近更新时间';
COMMENT ON COLUMN mine_location_tag.updated_by IS '定位标签最近更新用户标识';
COMMENT ON COLUMN mine_location_tag.archived_at IS '定位标签归档时间';
COMMENT ON COLUMN mine_location_tag.version IS '定位标签乐观锁版本号';

COMMENT ON TABLE mine_coordinate_system IS '矿区原始或统一空间坐标系定义';
COMMENT ON COLUMN mine_coordinate_system.id IS '坐标系唯一标识';
COMMENT ON COLUMN mine_coordinate_system.organization_id IS '坐标系所属组织标识';
COMMENT ON COLUMN mine_coordinate_system.site_id IS '坐标系所属矿区标识';
COMMENT ON COLUMN mine_coordinate_system.system_code IS '矿区内唯一的坐标系稳定小写业务编码';
COMMENT ON COLUMN mine_coordinate_system.name IS '坐标系名称';
COMMENT ON COLUMN mine_coordinate_system.coordinate_type IS '本地直角、全球经纬度或东北天坐标类型';
COMMENT ON COLUMN mine_coordinate_system.unit IS '坐标数值单位';
COMMENT ON COLUMN mine_coordinate_system.epsg_code IS '可选的 EPSG 坐标参考编号';
COMMENT ON COLUMN mine_coordinate_system.unified IS '是否为矿区唯一统一展示坐标系';
COMMENT ON COLUMN mine_coordinate_system.min_x IS '坐标系横轴最小边界';
COMMENT ON COLUMN mine_coordinate_system.max_x IS '坐标系横轴最大边界';
COMMENT ON COLUMN mine_coordinate_system.min_y IS '坐标系纵轴最小边界';
COMMENT ON COLUMN mine_coordinate_system.max_y IS '坐标系纵轴最大边界';
COMMENT ON COLUMN mine_coordinate_system.min_z IS '坐标系高程最小边界';
COMMENT ON COLUMN mine_coordinate_system.max_z IS '坐标系高程最大边界';
COMMENT ON COLUMN mine_coordinate_system.status IS '坐标系活动或归档状态';
COMMENT ON COLUMN mine_coordinate_system.created_at IS '坐标系创建时间';
COMMENT ON COLUMN mine_coordinate_system.created_by IS '坐标系创建用户标识';
COMMENT ON COLUMN mine_coordinate_system.updated_at IS '坐标系最近更新时间';
COMMENT ON COLUMN mine_coordinate_system.updated_by IS '坐标系最近更新用户标识';
COMMENT ON COLUMN mine_coordinate_system.archived_at IS '坐标系归档时间';
COMMENT ON COLUMN mine_coordinate_system.version IS '坐标系乐观锁版本号';

COMMENT ON TABLE mine_coordinate_transform_version IS '矿区双坐标转换的不可变版本';
COMMENT ON COLUMN mine_coordinate_transform_version.id IS '坐标转换版本唯一标识';
COMMENT ON COLUMN mine_coordinate_transform_version.organization_id IS '坐标转换所属组织标识';
COMMENT ON COLUMN mine_coordinate_transform_version.site_id IS '坐标转换所属矿区标识';
COMMENT ON COLUMN mine_coordinate_transform_version.source_coordinate_system_id IS '源坐标系标识';
COMMENT ON COLUMN mine_coordinate_transform_version.target_coordinate_system_id IS '目标坐标系标识';
COMMENT ON COLUMN mine_coordinate_transform_version.version_number IS '同一源目标坐标对的递增版本号';
COMMENT ON COLUMN mine_coordinate_transform_version.algorithm IS '坐标转换算法';
COMMENT ON COLUMN mine_coordinate_transform_version.parameters IS '坐标转换算法参数对象';
COMMENT ON COLUMN mine_coordinate_transform_version.valid_from IS '转换版本生效起始时间';
COMMENT ON COLUMN mine_coordinate_transform_version.valid_to IS '转换版本生效结束时间';
COMMENT ON COLUMN mine_coordinate_transform_version.status IS '转换草稿或已发布状态';
COMMENT ON COLUMN mine_coordinate_transform_version.content_hash IS '发布时规范化转换内容的 SHA-256';
COMMENT ON COLUMN mine_coordinate_transform_version.published_at IS '转换版本发布时间';
COMMENT ON COLUMN mine_coordinate_transform_version.published_by IS '转换版本发布用户标识';
COMMENT ON COLUMN mine_coordinate_transform_version.created_at IS '转换版本创建时间';
COMMENT ON COLUMN mine_coordinate_transform_version.created_by IS '转换版本创建用户标识';
