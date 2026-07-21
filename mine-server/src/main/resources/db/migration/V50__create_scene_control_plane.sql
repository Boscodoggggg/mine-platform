-- 建立可版本化发布的三维场景与内容寻址资源控制面。
CREATE FUNCTION mine_is_safe_scene_asset_path(candidate TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT length(candidate) BETWEEN 1 AND 1024
        AND candidate = btrim(candidate)
        AND candidate NOT LIKE '/%'
        AND candidate NOT LIKE '%/'
        AND candidate NOT LIKE '%//%'
        AND position(E'\\' IN candidate) = 0
        AND position(':' IN candidate) = 0
        AND candidate !~ '(^|/)\.{1,2}(/|$)'
        AND candidate !~ '[[:cntrl:]]'
$$;

CREATE TABLE mine_scene_asset (
    id                  UUID          NOT NULL,
    organization_id     UUID          NOT NULL,
    site_id             UUID          NOT NULL,
    asset_type          VARCHAR(16)   NOT NULL,
    original_filename   VARCHAR(255)  NOT NULL,
    content_type        VARCHAR(128)  NOT NULL,
    content_digest      VARCHAR(71)   NOT NULL,
    source_size_bytes   BIGINT        NOT NULL,
    expanded_size_bytes BIGINT        NOT NULL,
    file_count          INTEGER       NOT NULL,
    entry_path          VARCHAR(1024) NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'QUARANTINED',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    archived_at         TIMESTAMPTZ,
    CONSTRAINT pk_mine_scene_asset PRIMARY KEY (id),
    CONSTRAINT uk_mine_scene_asset_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT uk_mine_scene_asset_site_digest UNIQUE (site_id, content_digest),
    CONSTRAINT fk_mine_scene_asset_site_organization
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_scene_asset_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_scene_asset_type
        CHECK (asset_type IN ('GLB', 'TILES_3D')),
    CONSTRAINT ck_mine_scene_asset_filename CHECK (
        btrim(original_filename) <> ''
        AND position('/' IN original_filename) = 0
        AND position(E'\\' IN original_filename) = 0
        AND original_filename !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_mine_scene_asset_content_type CHECK (
        (asset_type = 'GLB'
            AND content_type IN ('model/gltf-binary', 'application/octet-stream'))
        OR (asset_type = 'TILES_3D'
            AND content_type IN ('application/zip', 'application/x-zip-compressed'))
    ),
    CONSTRAINT ck_mine_scene_asset_digest
        CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_mine_scene_asset_size CHECK (
        source_size_bytes BETWEEN 1 AND 10737418240
        AND expanded_size_bytes BETWEEN 1 AND 53687091200
    ),
    CONSTRAINT ck_mine_scene_asset_file_count
        CHECK (file_count BETWEEN 1 AND 10000),
    CONSTRAINT ck_mine_scene_asset_entry CHECK (
        mine_is_safe_scene_asset_path(entry_path)
        AND (
            (asset_type = 'GLB'
                AND file_count = 1
                AND source_size_bytes = expanded_size_bytes
                AND lower(entry_path) LIKE '%.glb')
            OR (asset_type = 'TILES_3D'
                AND (lower(entry_path) = 'tileset.json'
                    OR lower(entry_path) LIKE '%/tileset.json'))
        )
    ),
    CONSTRAINT ck_mine_scene_asset_status
        CHECK (status IN ('QUARANTINED', 'AVAILABLE', 'ARCHIVED')),
    CONSTRAINT ck_mine_scene_asset_lifecycle CHECK (
        (status <> 'ARCHIVED' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    )
);

CREATE INDEX idx_mine_scene_asset_site_status_created
    ON mine_scene_asset(site_id, status, created_at DESC, id);

CREATE TABLE mine_scene_asset_file (
    id              UUID          NOT NULL,
    organization_id UUID          NOT NULL,
    site_id         UUID          NOT NULL,
    asset_id        UUID          NOT NULL,
    relative_path   VARCHAR(1024) NOT NULL,
    content_type    VARCHAR(128)  NOT NULL,
    content_digest  VARCHAR(71)   NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_scene_asset_file PRIMARY KEY (id),
    CONSTRAINT uk_mine_scene_asset_file_path UNIQUE (asset_id, relative_path),
    CONSTRAINT fk_mine_scene_asset_file_scope
        FOREIGN KEY (asset_id, organization_id, site_id)
        REFERENCES mine_scene_asset(id, organization_id, site_id) ON DELETE CASCADE,
    CONSTRAINT ck_mine_scene_asset_file_path
        CHECK (mine_is_safe_scene_asset_path(relative_path)),
    CONSTRAINT ck_mine_scene_asset_file_content_type CHECK (
        btrim(content_type) <> ''
        AND length(content_type) <= 128
        AND content_type !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_mine_scene_asset_file_digest
        CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_mine_scene_asset_file_size
        CHECK (size_bytes BETWEEN 1 AND 10737418240)
);

CREATE UNIQUE INDEX uk_mine_scene_asset_file_path_ci
    ON mine_scene_asset_file(asset_id, lower(relative_path));
CREATE INDEX idx_mine_scene_asset_file_scope
    ON mine_scene_asset_file(site_id, asset_id, relative_path);

CREATE TABLE mine_scene (
    id              UUID         NOT NULL,
    organization_id UUID         NOT NULL,
    site_id         UUID         NOT NULL,
    scene_code      VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(500),
    default_scene   BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    archived_at     TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_scene PRIMARY KEY (id),
    CONSTRAINT uk_mine_scene_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT uk_mine_scene_site_code UNIQUE (site_id, scene_code),
    CONSTRAINT fk_mine_scene_site_organization
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_scene_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_scene_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_scene_code
        CHECK (scene_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_scene_name
        CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_scene_description
        CHECK (description IS NULL OR btrim(description) <> ''),
    CONSTRAINT ck_mine_scene_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mine_scene_lifecycle CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (
            status = 'ARCHIVED'
            AND NOT default_scene
            AND archived_at IS NOT NULL
            AND archived_at >= created_at
        )
    ),
    CONSTRAINT ck_mine_scene_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE UNIQUE INDEX uk_mine_scene_site_default
    ON mine_scene(site_id)
    WHERE default_scene AND status = 'ACTIVE';
CREATE INDEX idx_mine_scene_site_status_name
    ON mine_scene(site_id, status, name, id);

CREATE TABLE mine_scene_version (
    id                   UUID             NOT NULL,
    organization_id      UUID             NOT NULL,
    site_id              UUID             NOT NULL,
    scene_id             UUID             NOT NULL,
    coordinate_system_id UUID             NOT NULL,
    revision_number      INTEGER          NOT NULL,
    schema_version       INTEGER          NOT NULL DEFAULT 1,
    coordinate_mode      VARCHAR(24)      NOT NULL,
    origin_x             DOUBLE PRECISION NOT NULL,
    origin_y             DOUBLE PRECISION NOT NULL,
    origin_z             DOUBLE PRECISION NOT NULL,
    heading_degrees      DOUBLE PRECISION NOT NULL,
    pitch_degrees        DOUBLE PRECISION NOT NULL,
    roll_degrees         DOUBLE PRECISION NOT NULL,
    scale                 DOUBLE PRECISION NOT NULL,
    default_camera       JSONB            NOT NULL,
    fallback_viewport    JSONB            NOT NULL,
    status               VARCHAR(16)      NOT NULL DEFAULT 'DRAFT',
    content_hash         VARCHAR(71),
    published_at         TIMESTAMPTZ,
    published_by         UUID,
    superseded_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           UUID,
    CONSTRAINT pk_mine_scene_version PRIMARY KEY (id),
    CONSTRAINT uk_mine_scene_version_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT uk_mine_scene_version_revision UNIQUE (scene_id, revision_number),
    CONSTRAINT fk_mine_scene_version_scene_scope
        FOREIGN KEY (scene_id, organization_id, site_id)
        REFERENCES mine_scene(id, organization_id, site_id) ON DELETE CASCADE,
    CONSTRAINT fk_mine_scene_version_coordinate_scope
        FOREIGN KEY (coordinate_system_id, organization_id, site_id)
        REFERENCES mine_coordinate_system(id, organization_id, site_id),
    CONSTRAINT fk_mine_scene_version_published_by
        FOREIGN KEY (published_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_scene_version_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_scene_version_revision
        CHECK (revision_number > 0 AND schema_version BETWEEN 1 AND 1000),
    CONSTRAINT ck_mine_scene_version_coordinate_mode
        CHECK (coordinate_mode IN ('LOCAL_ENU', 'WGS84_ANCHOR')),
    CONSTRAINT ck_mine_scene_version_transform CHECK (
        origin_x > '-Infinity'::DOUBLE PRECISION
        AND origin_x < 'Infinity'::DOUBLE PRECISION
        AND origin_y > '-Infinity'::DOUBLE PRECISION
        AND origin_y < 'Infinity'::DOUBLE PRECISION
        AND origin_z > '-Infinity'::DOUBLE PRECISION
        AND origin_z < 'Infinity'::DOUBLE PRECISION
        AND heading_degrees BETWEEN -360 AND 360
        AND pitch_degrees BETWEEN -360 AND 360
        AND roll_degrees BETWEEN -360 AND 360
        AND scale > 0
        AND scale <= 1000000
        AND scale < 'Infinity'::DOUBLE PRECISION
    ),
    CONSTRAINT ck_mine_scene_version_view_config CHECK (
        jsonb_typeof(default_camera) = 'object'
        AND octet_length(default_camera::TEXT) <= 16384
        AND jsonb_typeof(fallback_viewport) = 'object'
        AND octet_length(fallback_viewport::TEXT) <= 16384
    ),
    CONSTRAINT ck_mine_scene_version_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT ck_mine_scene_version_publication CHECK (
        (
            status = 'DRAFT'
            AND content_hash IS NULL
            AND published_at IS NULL
            AND published_by IS NULL
            AND superseded_at IS NULL
        )
        OR (
            status = 'PUBLISHED'
            AND content_hash ~ '^sha256:[0-9a-f]{64}$'
            AND published_at IS NOT NULL
            AND published_at >= created_at
            AND superseded_at IS NULL
        )
        OR (
            status = 'SUPERSEDED'
            AND content_hash ~ '^sha256:[0-9a-f]{64}$'
            AND published_at IS NOT NULL
            AND superseded_at IS NOT NULL
            AND superseded_at >= published_at
        )
    )
);

CREATE UNIQUE INDEX uk_mine_scene_version_single_draft
    ON mine_scene_version(scene_id)
    WHERE status = 'DRAFT';
CREATE UNIQUE INDEX uk_mine_scene_version_single_published
    ON mine_scene_version(scene_id)
    WHERE status = 'PUBLISHED';
CREATE INDEX idx_mine_scene_version_scene_revision
    ON mine_scene_version(scene_id, revision_number DESC, id);

CREATE TABLE mine_scene_layer (
    id                   UUID             NOT NULL,
    organization_id      UUID             NOT NULL,
    site_id              UUID             NOT NULL,
    scene_version_id     UUID             NOT NULL,
    layer_code           VARCHAR(64)      NOT NULL,
    order_number         INTEGER          NOT NULL,
    layer_type           VARCHAR(32)      NOT NULL,
    asset_id             UUID,
    visible              BOOLEAN          NOT NULL DEFAULT TRUE,
    opacity              DOUBLE PRECISION NOT NULL DEFAULT 1,
    minimum_distance     DOUBLE PRECISION NOT NULL DEFAULT 0,
    maximum_distance     DOUBLE PRECISION NOT NULL DEFAULT 1000000000,
    offset_x             DOUBLE PRECISION NOT NULL DEFAULT 0,
    offset_y             DOUBLE PRECISION NOT NULL DEFAULT 0,
    offset_z             DOUBLE PRECISION NOT NULL DEFAULT 0,
    heading_degrees      DOUBLE PRECISION NOT NULL DEFAULT 0,
    pitch_degrees        DOUBLE PRECISION NOT NULL DEFAULT 0,
    roll_degrees         DOUBLE PRECISION NOT NULL DEFAULT 0,
    scale                 DOUBLE PRECISION NOT NULL DEFAULT 1,
    max_items             INTEGER          NOT NULL DEFAULT 0,
    options               JSONB            NOT NULL DEFAULT '{}'::JSONB,
    created_at            TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_scene_layer PRIMARY KEY (id),
    CONSTRAINT uk_mine_scene_layer_code UNIQUE (scene_version_id, layer_code),
    CONSTRAINT uk_mine_scene_layer_order UNIQUE (scene_version_id, order_number),
    CONSTRAINT fk_mine_scene_layer_version_scope
        FOREIGN KEY (scene_version_id, organization_id, site_id)
        REFERENCES mine_scene_version(id, organization_id, site_id) ON DELETE CASCADE,
    CONSTRAINT fk_mine_scene_layer_asset_scope
        FOREIGN KEY (asset_id, organization_id, site_id)
        REFERENCES mine_scene_asset(id, organization_id, site_id),
    CONSTRAINT ck_mine_scene_layer_code
        CHECK (layer_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_scene_layer_order
        CHECK (order_number BETWEEN 1 AND 10000),
    CONSTRAINT ck_mine_scene_layer_type CHECK (
        layer_type IN (
            'STATIC_3D_TILES', 'STATIC_GLB', 'GRID',
            'DYNAMIC_LOCATIONS', 'DYNAMIC_REGIONS', 'DYNAMIC_TRAJECTORY',
            'DYNAMIC_ALARMS', 'DYNAMIC_OPERATIONS'
        )
    ),
    CONSTRAINT ck_mine_scene_layer_asset_presence CHECK (
        (layer_type IN ('STATIC_3D_TILES', 'STATIC_GLB') AND asset_id IS NOT NULL)
        OR (layer_type NOT IN ('STATIC_3D_TILES', 'STATIC_GLB') AND asset_id IS NULL)
    ),
    CONSTRAINT ck_mine_scene_layer_visibility CHECK (
        opacity >= 0
        AND opacity <= 1
        AND opacity < 'Infinity'::DOUBLE PRECISION
        AND minimum_distance >= 0
        AND minimum_distance <= maximum_distance
        AND maximum_distance <= 1000000000
        AND maximum_distance < 'Infinity'::DOUBLE PRECISION
    ),
    CONSTRAINT ck_mine_scene_layer_transform CHECK (
        offset_x > '-Infinity'::DOUBLE PRECISION
        AND offset_x < 'Infinity'::DOUBLE PRECISION
        AND offset_y > '-Infinity'::DOUBLE PRECISION
        AND offset_y < 'Infinity'::DOUBLE PRECISION
        AND offset_z > '-Infinity'::DOUBLE PRECISION
        AND offset_z < 'Infinity'::DOUBLE PRECISION
        AND heading_degrees BETWEEN -360 AND 360
        AND pitch_degrees BETWEEN -360 AND 360
        AND roll_degrees BETWEEN -360 AND 360
        AND scale > 0
        AND scale <= 1000000
        AND scale < 'Infinity'::DOUBLE PRECISION
    ),
    CONSTRAINT ck_mine_scene_layer_item_limit CHECK (
        (layer_type IN (
            'DYNAMIC_LOCATIONS', 'DYNAMIC_REGIONS', 'DYNAMIC_TRAJECTORY',
            'DYNAMIC_ALARMS', 'DYNAMIC_OPERATIONS'
        ) AND max_items BETWEEN 1 AND 100000)
        OR (layer_type IN ('STATIC_3D_TILES', 'STATIC_GLB', 'GRID') AND max_items = 0)
    ),
    CONSTRAINT ck_mine_scene_layer_options CHECK (
        jsonb_typeof(options) = 'object'
        AND octet_length(options::TEXT) <= 16384
    )
);

CREATE INDEX idx_mine_scene_layer_version_order
    ON mine_scene_layer(scene_version_id, order_number, id);
CREATE INDEX idx_mine_scene_layer_asset
    ON mine_scene_layer(asset_id)
    WHERE asset_id IS NOT NULL;

CREATE FUNCTION mine_guard_scene_asset_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'QUARANTINED' THEN
            RAISE EXCEPTION '可用或归档三维资源不可删除' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;

    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
        OR NEW.site_id IS DISTINCT FROM OLD.site_id
        OR NEW.asset_type IS DISTINCT FROM OLD.asset_type
        OR NEW.original_filename IS DISTINCT FROM OLD.original_filename
        OR NEW.content_type IS DISTINCT FROM OLD.content_type
        OR NEW.content_digest IS DISTINCT FROM OLD.content_digest
        OR NEW.source_size_bytes IS DISTINCT FROM OLD.source_size_bytes
        OR NEW.expanded_size_bytes IS DISTINCT FROM OLD.expanded_size_bytes
        OR NEW.file_count IS DISTINCT FROM OLD.file_count
        OR NEW.entry_path IS DISTINCT FROM OLD.entry_path
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION '三维资源内容元数据不可修改' USING ERRCODE = '23514';
    END IF;

    IF NOT (
        NEW.status = OLD.status
        OR (OLD.status = 'QUARANTINED' AND NEW.status IN ('AVAILABLE', 'ARCHIVED'))
        OR (OLD.status = 'AVAILABLE' AND NEW.status = 'ARCHIVED')
    ) THEN
        RAISE EXCEPTION '三维资源状态迁移不合法' USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'QUARANTINED' AND NEW.status = 'AVAILABLE' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM mine_scene_asset_file file
            WHERE file.asset_id = OLD.id
              AND file.relative_path = OLD.entry_path
        ) OR (SELECT COUNT(*) FROM mine_scene_asset_file file
              WHERE file.asset_id = OLD.id) <> OLD.file_count
           OR (SELECT COALESCE(SUM(file.size_bytes), 0)
               FROM mine_scene_asset_file file
               WHERE file.asset_id = OLD.id) <> OLD.expanded_size_bytes THEN
            RAISE EXCEPTION '三维资源文件清单与入口元数据不一致' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mine_guard_scene_asset_mutation
BEFORE UPDATE OR DELETE ON mine_scene_asset
FOR EACH ROW
EXECUTE FUNCTION mine_guard_scene_asset_mutation();

CREATE FUNCTION mine_guard_scene_asset_file_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_asset_id UUID;
    target_status VARCHAR(16);
BEGIN
    target_asset_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.asset_id ELSE NEW.asset_id END;
    SELECT status INTO target_status
    FROM mine_scene_asset
    WHERE id = target_asset_id
    FOR SHARE;
    IF target_status IS DISTINCT FROM 'QUARANTINED' THEN
        RAISE EXCEPTION '可用或归档三维资源的文件清单不可修改' USING ERRCODE = '23514';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

CREATE TRIGGER trg_mine_guard_scene_asset_file_mutation
BEFORE INSERT OR UPDATE OR DELETE ON mine_scene_asset_file
FOR EACH ROW
EXECUTE FUNCTION mine_guard_scene_asset_file_mutation();

CREATE FUNCTION mine_validate_scene_layer_resource()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    referenced_type VARCHAR(16);
    referenced_status VARCHAR(16);
    parent_status VARCHAR(16);
BEGIN
    SELECT status INTO parent_status
    FROM mine_scene_version
    WHERE id = NEW.scene_version_id
    FOR SHARE;
    IF parent_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION '仅场景草稿允许新增或修改图层' USING ERRCODE = '23514';
    END IF;

    IF NEW.asset_id IS NOT NULL THEN
        SELECT asset_type, status
        INTO referenced_type, referenced_status
        FROM mine_scene_asset
        WHERE id = NEW.asset_id
        FOR SHARE;
        IF referenced_status IS DISTINCT FROM 'AVAILABLE'
            OR (NEW.layer_type = 'STATIC_GLB' AND referenced_type IS DISTINCT FROM 'GLB')
            OR (NEW.layer_type = 'STATIC_3D_TILES'
                AND referenced_type IS DISTINCT FROM 'TILES_3D') THEN
            RAISE EXCEPTION '场景图层资源类型或状态不匹配' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mine_validate_scene_layer_resource
BEFORE INSERT OR UPDATE ON mine_scene_layer
FOR EACH ROW
EXECUTE FUNCTION mine_validate_scene_layer_resource();

CREATE FUNCTION mine_guard_scene_layer_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    SELECT status INTO parent_status
    FROM mine_scene_version
    WHERE id = OLD.scene_version_id
    FOR SHARE;
    IF parent_status IN ('PUBLISHED', 'SUPERSEDED') THEN
        RAISE EXCEPTION '已发布场景版本的图层不可删除' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER trg_mine_guard_scene_layer_delete
BEFORE DELETE ON mine_scene_layer
FOR EACH ROW
EXECUTE FUNCTION mine_guard_scene_layer_delete();

CREATE FUNCTION mine_guard_scene_version_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    invalid_resource_count BIGINT;
    layer_count BIGINT;
    coordinate_ready BOOLEAN;
    scene_ready BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('PUBLISHED', 'SUPERSEDED') THEN
            RAISE EXCEPTION '已发布或已替代场景版本不可删除' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status IN ('PUBLISHED', 'SUPERSEDED') THEN
        IF OLD.status = 'PUBLISHED'
            AND NEW.status = 'SUPERSEDED'
            AND NEW.superseded_at IS NOT NULL
            AND NEW.organization_id IS NOT DISTINCT FROM OLD.organization_id
            AND NEW.site_id IS NOT DISTINCT FROM OLD.site_id
            AND NEW.scene_id IS NOT DISTINCT FROM OLD.scene_id
            AND NEW.coordinate_system_id IS NOT DISTINCT FROM OLD.coordinate_system_id
            AND NEW.revision_number IS NOT DISTINCT FROM OLD.revision_number
            AND NEW.schema_version IS NOT DISTINCT FROM OLD.schema_version
            AND NEW.coordinate_mode IS NOT DISTINCT FROM OLD.coordinate_mode
            AND NEW.origin_x IS NOT DISTINCT FROM OLD.origin_x
            AND NEW.origin_y IS NOT DISTINCT FROM OLD.origin_y
            AND NEW.origin_z IS NOT DISTINCT FROM OLD.origin_z
            AND NEW.heading_degrees IS NOT DISTINCT FROM OLD.heading_degrees
            AND NEW.pitch_degrees IS NOT DISTINCT FROM OLD.pitch_degrees
            AND NEW.roll_degrees IS NOT DISTINCT FROM OLD.roll_degrees
            AND NEW.scale IS NOT DISTINCT FROM OLD.scale
            AND NEW.default_camera IS NOT DISTINCT FROM OLD.default_camera
            AND NEW.fallback_viewport IS NOT DISTINCT FROM OLD.fallback_viewport
            AND NEW.content_hash IS NOT DISTINCT FROM OLD.content_hash
            AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
            AND NEW.published_by IS NOT DISTINCT FROM OLD.published_by
            AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
            AND NEW.created_by IS NOT DISTINCT FROM OLD.created_by THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION '已发布或已替代场景版本内容不可修改' USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'SUPERSEDED' THEN
        RAISE EXCEPTION '草稿不能直接进入已替代状态' USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED' THEN
        SELECT status = 'ACTIVE'
        INTO scene_ready
        FROM mine_scene
        WHERE id = OLD.scene_id
        FOR SHARE;
        SELECT status = 'ACTIVE' AND unified
        INTO coordinate_ready
        FROM mine_coordinate_system
        WHERE id = OLD.coordinate_system_id
        FOR SHARE;
        SELECT COUNT(*)
        INTO layer_count
        FROM mine_scene_layer layer
        WHERE layer.scene_version_id = OLD.id;
        SELECT COUNT(*)
        INTO invalid_resource_count
        FROM mine_scene_layer layer
        LEFT JOIN mine_scene_asset asset ON asset.id = layer.asset_id
        WHERE layer.scene_version_id = OLD.id
          AND layer.layer_type IN ('STATIC_GLB', 'STATIC_3D_TILES')
          AND (
              asset.id IS NULL
              OR asset.status <> 'AVAILABLE'
              OR (layer.layer_type = 'STATIC_GLB' AND asset.asset_type <> 'GLB')
              OR (layer.layer_type = 'STATIC_3D_TILES' AND asset.asset_type <> 'TILES_3D')
          );
        IF scene_ready IS DISTINCT FROM TRUE
            OR coordinate_ready IS DISTINCT FROM TRUE
            OR layer_count = 0
            OR invalid_resource_count <> 0 THEN
            RAISE EXCEPTION '场景草稿不满足发布条件' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION '场景版本状态迁移不合法' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mine_guard_scene_version_mutation
BEFORE UPDATE OR DELETE ON mine_scene_version
FOR EACH ROW
EXECUTE FUNCTION mine_guard_scene_version_mutation();

COMMENT ON TABLE mine_scene_asset IS '矿区三维模型或三维瓦片包的内容寻址资源登记';
COMMENT ON COLUMN mine_scene_asset.id IS '三维资源唯一标识';
COMMENT ON COLUMN mine_scene_asset.organization_id IS '三维资源所属组织标识';
COMMENT ON COLUMN mine_scene_asset.site_id IS '三维资源所属矿区标识';
COMMENT ON COLUMN mine_scene_asset.asset_type IS '三维资源固定类型';
COMMENT ON COLUMN mine_scene_asset.original_filename IS '上传时不含路径的原始文件名';
COMMENT ON COLUMN mine_scene_asset.content_type IS '经过白名单校验的上传媒体类型';
COMMENT ON COLUMN mine_scene_asset.content_digest IS '上传原始内容的 SHA-256 摘要';
COMMENT ON COLUMN mine_scene_asset.source_size_bytes IS '上传原始内容字节数';
COMMENT ON COLUMN mine_scene_asset.expanded_size_bytes IS '校验后文件清单展开总字节数';
COMMENT ON COLUMN mine_scene_asset.file_count IS '校验后文件清单条目数';
COMMENT ON COLUMN mine_scene_asset.entry_path IS '资源包内安全入口相对路径';
COMMENT ON COLUMN mine_scene_asset.status IS '资源隔离、可用或归档状态';
COMMENT ON COLUMN mine_scene_asset.created_at IS '资源登记时间';
COMMENT ON COLUMN mine_scene_asset.created_by IS '资源上传用户标识';
COMMENT ON COLUMN mine_scene_asset.archived_at IS '资源归档时间';

COMMENT ON TABLE mine_scene_asset_file IS '三维资源中允许受权读取的不可变文件清单';
COMMENT ON COLUMN mine_scene_asset_file.id IS '资源文件唯一标识';
COMMENT ON COLUMN mine_scene_asset_file.organization_id IS '资源文件所属组织标识';
COMMENT ON COLUMN mine_scene_asset_file.site_id IS '资源文件所属矿区标识';
COMMENT ON COLUMN mine_scene_asset_file.asset_id IS '所属三维资源标识';
COMMENT ON COLUMN mine_scene_asset_file.relative_path IS '包内大小写不冲突的安全相对路径';
COMMENT ON COLUMN mine_scene_asset_file.content_type IS '文件响应使用的白名单媒体类型';
COMMENT ON COLUMN mine_scene_asset_file.content_digest IS '文件内容 SHA-256 摘要';
COMMENT ON COLUMN mine_scene_asset_file.size_bytes IS '文件内容字节数';
COMMENT ON COLUMN mine_scene_asset_file.created_at IS '文件清单登记时间';

COMMENT ON TABLE mine_scene IS '矿区内可设置默认值的稳定三维场景对象';
COMMENT ON COLUMN mine_scene.id IS '场景唯一标识';
COMMENT ON COLUMN mine_scene.organization_id IS '场景所属组织标识';
COMMENT ON COLUMN mine_scene.site_id IS '场景所属矿区标识';
COMMENT ON COLUMN mine_scene.scene_code IS '矿区内唯一场景稳定编码';
COMMENT ON COLUMN mine_scene.name IS '场景名称';
COMMENT ON COLUMN mine_scene.description IS '场景业务说明';
COMMENT ON COLUMN mine_scene.default_scene IS '是否为矿区当前默认场景';
COMMENT ON COLUMN mine_scene.status IS '场景活动或归档状态';
COMMENT ON COLUMN mine_scene.created_at IS '场景创建时间';
COMMENT ON COLUMN mine_scene.created_by IS '场景创建用户标识';
COMMENT ON COLUMN mine_scene.updated_at IS '场景最近更新时间';
COMMENT ON COLUMN mine_scene.updated_by IS '场景最近更新用户标识';
COMMENT ON COLUMN mine_scene.archived_at IS '场景归档时间';
COMMENT ON COLUMN mine_scene.version IS '场景乐观锁版本号';

COMMENT ON TABLE mine_scene_version IS '场景可编辑草稿和不可变发布版本';
COMMENT ON COLUMN mine_scene_version.id IS '场景版本唯一标识';
COMMENT ON COLUMN mine_scene_version.organization_id IS '场景版本所属组织标识';
COMMENT ON COLUMN mine_scene_version.site_id IS '场景版本所属矿区标识';
COMMENT ON COLUMN mine_scene_version.scene_id IS '所属稳定场景标识';
COMMENT ON COLUMN mine_scene_version.coordinate_system_id IS '矿区统一坐标系标识';
COMMENT ON COLUMN mine_scene_version.revision_number IS '场景内连续递增修订号';
COMMENT ON COLUMN mine_scene_version.schema_version IS '运行快照结构版本号';
COMMENT ON COLUMN mine_scene_version.coordinate_mode IS '局部坐标或全球锚点模式';
COMMENT ON COLUMN mine_scene_version.origin_x IS '场景原点 X 坐标或经度';
COMMENT ON COLUMN mine_scene_version.origin_y IS '场景原点 Y 坐标或纬度';
COMMENT ON COLUMN mine_scene_version.origin_z IS '场景原点 Z 坐标或高度';
COMMENT ON COLUMN mine_scene_version.heading_degrees IS '场景航向旋转角度';
COMMENT ON COLUMN mine_scene_version.pitch_degrees IS '场景俯仰旋转角度';
COMMENT ON COLUMN mine_scene_version.roll_degrees IS '场景横滚旋转角度';
COMMENT ON COLUMN mine_scene_version.scale IS '场景统一缩放比例';
COMMENT ON COLUMN mine_scene_version.default_camera IS '受大小限制的默认三维相机配置';
COMMENT ON COLUMN mine_scene_version.fallback_viewport IS '受大小限制的二维降级视口配置';
COMMENT ON COLUMN mine_scene_version.status IS '场景草稿、当前发布或已替代状态';
COMMENT ON COLUMN mine_scene_version.content_hash IS '规范化场景内容 SHA-256 摘要';
COMMENT ON COLUMN mine_scene_version.published_at IS '场景版本发布时间';
COMMENT ON COLUMN mine_scene_version.published_by IS '场景版本发布用户标识';
COMMENT ON COLUMN mine_scene_version.superseded_at IS '场景版本被后续发布替代时间';
COMMENT ON COLUMN mine_scene_version.created_at IS '场景版本创建时间';
COMMENT ON COLUMN mine_scene_version.created_by IS '场景版本创建用户标识';

COMMENT ON TABLE mine_scene_layer IS '场景版本内有序且类型固定的可视化图层';
COMMENT ON COLUMN mine_scene_layer.id IS '场景图层唯一标识';
COMMENT ON COLUMN mine_scene_layer.organization_id IS '场景图层所属组织标识';
COMMENT ON COLUMN mine_scene_layer.site_id IS '场景图层所属矿区标识';
COMMENT ON COLUMN mine_scene_layer.scene_version_id IS '所属场景版本标识';
COMMENT ON COLUMN mine_scene_layer.layer_code IS '版本内唯一稳定图层编码';
COMMENT ON COLUMN mine_scene_layer.order_number IS '版本内唯一图层显示顺序';
COMMENT ON COLUMN mine_scene_layer.layer_type IS '固定静态、网格或动态图层类型';
COMMENT ON COLUMN mine_scene_layer.asset_id IS '静态图层引用的同矿区三维资源标识';
COMMENT ON COLUMN mine_scene_layer.visible IS '图层默认是否可见';
COMMENT ON COLUMN mine_scene_layer.opacity IS '图层默认透明度';
COMMENT ON COLUMN mine_scene_layer.minimum_distance IS '图层最小显示距离';
COMMENT ON COLUMN mine_scene_layer.maximum_distance IS '图层最大显示距离';
COMMENT ON COLUMN mine_scene_layer.offset_x IS '图层局部 X 偏移';
COMMENT ON COLUMN mine_scene_layer.offset_y IS '图层局部 Y 偏移';
COMMENT ON COLUMN mine_scene_layer.offset_z IS '图层局部 Z 偏移';
COMMENT ON COLUMN mine_scene_layer.heading_degrees IS '图层局部航向旋转角度';
COMMENT ON COLUMN mine_scene_layer.pitch_degrees IS '图层局部俯仰旋转角度';
COMMENT ON COLUMN mine_scene_layer.roll_degrees IS '图层局部横滚旋转角度';
COMMENT ON COLUMN mine_scene_layer.scale IS '图层局部统一缩放比例';
COMMENT ON COLUMN mine_scene_layer.max_items IS '动态图层最大可见对象数量';
COMMENT ON COLUMN mine_scene_layer.options IS '受大小限制的图层白名单扩展配置';
COMMENT ON COLUMN mine_scene_layer.created_at IS '场景图层创建时间';
