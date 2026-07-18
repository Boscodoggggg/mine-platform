-- 建立矿区途径点、路线和电子围栏的草稿及不可变发布版本控制面。

CREATE FUNCTION mine_is_vehicle_type_array(candidate JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT jsonb_typeof(candidate) = 'array'
       AND jsonb_array_length(candidate) BETWEEN 1 AND 16
       AND NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements_text(candidate) AS vehicle_type(value)
            WHERE vehicle_type.value NOT IN (
                'HAUL_TRUCK', 'EXCAVATOR', 'LOADER', 'DRILL', 'DOZER',
                'WATER_TRUCK', 'LIGHT_VEHICLE', 'OTHER'
            )
       )
$$;

CREATE TABLE mine_waypoint (
    id              UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    site_id         UUID        NOT NULL,
    waypoint_code   VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    archived_at     TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_waypoint PRIMARY KEY (id),
    CONSTRAINT uk_mine_waypoint_site_code UNIQUE (site_id, waypoint_code),
    CONSTRAINT uk_mine_waypoint_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_waypoint_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_waypoint_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_waypoint_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_waypoint_code
        CHECK (waypoint_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_waypoint_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mine_waypoint_lifecycle CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_waypoint_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE TABLE mine_waypoint_version (
    id                        UUID             NOT NULL,
    waypoint_id               UUID             NOT NULL,
    organization_id           UUID             NOT NULL,
    site_id                   UUID             NOT NULL,
    coordinate_system_id      UUID             NOT NULL,
    version_number            INTEGER          NOT NULL,
    name                      VARCHAR(128)     NOT NULL,
    waypoint_type             VARCHAR(16)      NOT NULL,
    x                         DOUBLE PRECISION NOT NULL,
    y                         DOUBLE PRECISION NOT NULL,
    z                         DOUBLE PRECISION,
    radius_meters             DOUBLE PRECISION NOT NULL,
    min_z                     DOUBLE PRECISION,
    max_z                     DOUBLE PRECISION,
    boundary_tolerance_meters DOUBLE PRECISION NOT NULL,
    enter_confirm_points      SMALLINT         NOT NULL,
    exit_confirm_points       SMALLINT         NOT NULL,
    dwell_seconds             INTEGER          NOT NULL,
    allowed_lateness_seconds  INTEGER          NOT NULL,
    idle_flush_seconds        INTEGER          NOT NULL,
    valid_from                TIMESTAMPTZ      NOT NULL,
    valid_to                  TIMESTAMPTZ,
    status                    VARCHAR(16)      NOT NULL DEFAULT 'DRAFT',
    content_hash              VARCHAR(71),
    published_at              TIMESTAMPTZ,
    published_by              UUID,
    created_at                TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                UUID,
    updated_at                TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                UUID,
    version                   BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_waypoint_version PRIMARY KEY (id),
    CONSTRAINT uk_mine_waypoint_version_number UNIQUE (waypoint_id, version_number),
    CONSTRAINT uk_mine_waypoint_version_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_waypoint_version_waypoint_scope
        FOREIGN KEY (waypoint_id, organization_id, site_id)
        REFERENCES mine_waypoint(id, organization_id, site_id),
    CONSTRAINT fk_mine_waypoint_version_coordinate_scope
        FOREIGN KEY (coordinate_system_id, organization_id, site_id)
        REFERENCES mine_coordinate_system(id, organization_id, site_id),
    CONSTRAINT fk_mine_waypoint_version_published_by
        FOREIGN KEY (published_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_waypoint_version_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_waypoint_version_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_waypoint_version_number CHECK (version_number > 0),
    CONSTRAINT ck_mine_waypoint_version_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_waypoint_version_type CHECK (
        waypoint_type IN ('LOAD', 'UNLOAD', 'CHECK', 'WAIT', 'ENTRY', 'EXIT')
    ),
    CONSTRAINT ck_mine_waypoint_version_coordinate CHECK (
        x > '-Infinity'::DOUBLE PRECISION AND x < 'Infinity'::DOUBLE PRECISION
        AND y > '-Infinity'::DOUBLE PRECISION AND y < 'Infinity'::DOUBLE PRECISION
        AND (z IS NULL OR (z > '-Infinity'::DOUBLE PRECISION
                          AND z < 'Infinity'::DOUBLE PRECISION))
    ),
    CONSTRAINT ck_mine_waypoint_version_radius CHECK (
        radius_meters > 0 AND radius_meters <= 10000
        AND boundary_tolerance_meters >= 0
        AND boundary_tolerance_meters < radius_meters
    ),
    CONSTRAINT ck_mine_waypoint_version_height CHECK (
        (min_z IS NULL AND max_z IS NULL)
        OR (min_z IS NOT NULL AND max_z IS NOT NULL
            AND min_z < max_z
            AND min_z > '-Infinity'::DOUBLE PRECISION
            AND max_z < 'Infinity'::DOUBLE PRECISION)
    ),
    CONSTRAINT ck_mine_waypoint_version_tuning CHECK (
        enter_confirm_points BETWEEN 1 AND 20
        AND exit_confirm_points BETWEEN 1 AND 20
        AND dwell_seconds BETWEEN 0 AND 86400
        AND allowed_lateness_seconds BETWEEN 0 AND 300
        AND idle_flush_seconds BETWEEN 1 AND 3600
        AND idle_flush_seconds > allowed_lateness_seconds
    ),
    CONSTRAINT ck_mine_waypoint_version_validity
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mine_waypoint_version_status
        CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_mine_waypoint_version_publication CHECK (
        (status = 'DRAFT' AND content_hash IS NULL
            AND published_at IS NULL AND published_by IS NULL)
        OR (status = 'PUBLISHED'
            AND content_hash ~ '^sha256:[0-9a-f]{64}$'
            AND published_at IS NOT NULL AND published_at >= created_at)
    ),
    CONSTRAINT ck_mine_waypoint_version_audit
        CHECK (updated_at >= created_at AND version >= 0),
    CONSTRAINT ex_mine_waypoint_version_validity
        EXCLUDE USING gist (
            waypoint_id WITH =,
            tstzrange(valid_from, valid_to, '[)') WITH &&
        ) WHERE (status = 'PUBLISHED')
);

CREATE INDEX idx_mine_waypoint_site_status_code
    ON mine_waypoint(site_id, status, waypoint_code, id);
CREATE INDEX idx_mine_waypoint_version_site_status_validity
    ON mine_waypoint_version(site_id, status, valid_from DESC, id);

CREATE TABLE mine_route (
    id              UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    site_id         UUID        NOT NULL,
    route_code      VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    archived_at     TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_route PRIMARY KEY (id),
    CONSTRAINT uk_mine_route_site_code UNIQUE (site_id, route_code),
    CONSTRAINT uk_mine_route_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_route_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_route_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_route_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_route_code
        CHECK (route_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_route_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mine_route_lifecycle CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_route_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE TABLE mine_route_version (
    id                       UUID        NOT NULL,
    route_id                 UUID        NOT NULL,
    organization_id          UUID        NOT NULL,
    site_id                  UUID        NOT NULL,
    coordinate_system_id     UUID        NOT NULL,
    version_number           INTEGER     NOT NULL,
    name                     VARCHAR(128) NOT NULL,
    direction                VARCHAR(16) NOT NULL,
    applicable_vehicle_types JSONB       NOT NULL,
    valid_from               TIMESTAMPTZ NOT NULL,
    valid_to                 TIMESTAMPTZ,
    status                   VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    content_hash             VARCHAR(71),
    published_at             TIMESTAMPTZ,
    published_by             UUID,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               UUID,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by               UUID,
    version                  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_route_version PRIMARY KEY (id),
    CONSTRAINT uk_mine_route_version_number UNIQUE (route_id, version_number),
    CONSTRAINT uk_mine_route_version_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_route_version_route_scope
        FOREIGN KEY (route_id, organization_id, site_id)
        REFERENCES mine_route(id, organization_id, site_id),
    CONSTRAINT fk_mine_route_version_coordinate_scope
        FOREIGN KEY (coordinate_system_id, organization_id, site_id)
        REFERENCES mine_coordinate_system(id, organization_id, site_id),
    CONSTRAINT fk_mine_route_version_published_by
        FOREIGN KEY (published_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_route_version_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_route_version_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_route_version_number CHECK (version_number > 0),
    CONSTRAINT ck_mine_route_version_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_route_version_direction
        CHECK (direction IN ('FORWARD', 'REVERSE', 'BIDIRECTIONAL')),
    CONSTRAINT ck_mine_route_version_vehicle_types
        CHECK (mine_is_vehicle_type_array(applicable_vehicle_types)),
    CONSTRAINT ck_mine_route_version_validity
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mine_route_version_status
        CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_mine_route_version_publication CHECK (
        (status = 'DRAFT' AND content_hash IS NULL
            AND published_at IS NULL AND published_by IS NULL)
        OR (status = 'PUBLISHED'
            AND content_hash ~ '^sha256:[0-9a-f]{64}$'
            AND published_at IS NOT NULL AND published_at >= created_at)
    ),
    CONSTRAINT ck_mine_route_version_audit
        CHECK (updated_at >= created_at AND version >= 0),
    CONSTRAINT ex_mine_route_version_validity
        EXCLUDE USING gist (
            route_id WITH =,
            tstzrange(valid_from, valid_to, '[)') WITH &&
        ) WHERE (status = 'PUBLISHED')
);

CREATE TABLE mine_route_version_waypoint (
    route_version_id    UUID        NOT NULL,
    waypoint_version_id UUID        NOT NULL,
    organization_id     UUID        NOT NULL,
    site_id             UUID        NOT NULL,
    sequence_number     INTEGER     NOT NULL,
    stop_required       BOOLEAN     NOT NULL DEFAULT FALSE,
    expected_dwell_seconds INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_route_version_waypoint
        PRIMARY KEY (route_version_id, sequence_number),
    CONSTRAINT fk_mine_route_waypoint_route_scope
        FOREIGN KEY (route_version_id, organization_id, site_id)
        REFERENCES mine_route_version(id, organization_id, site_id),
    CONSTRAINT fk_mine_route_waypoint_waypoint_scope
        FOREIGN KEY (waypoint_version_id, organization_id, site_id)
        REFERENCES mine_waypoint_version(id, organization_id, site_id),
    CONSTRAINT ck_mine_route_waypoint_sequence
        CHECK (sequence_number BETWEEN 1 AND 10000),
    CONSTRAINT ck_mine_route_waypoint_dwell
        CHECK (expected_dwell_seconds IS NULL
            OR expected_dwell_seconds BETWEEN 0 AND 86400)
);

CREATE INDEX idx_mine_route_site_status_code
    ON mine_route(site_id, status, route_code, id);
CREATE INDEX idx_mine_route_version_site_status_validity
    ON mine_route_version(site_id, status, valid_from DESC, id);
CREATE INDEX idx_mine_route_waypoint_reference
    ON mine_route_version_waypoint(waypoint_version_id, route_version_id);

CREATE TABLE mine_geofence (
    id              UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    site_id         UUID        NOT NULL,
    geofence_code   VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      UUID,
    archived_at     TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_geofence PRIMARY KEY (id),
    CONSTRAINT uk_mine_geofence_site_code UNIQUE (site_id, geofence_code),
    CONSTRAINT uk_mine_geofence_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_geofence_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_geofence_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_geofence_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_geofence_code
        CHECK (geofence_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_geofence_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mine_geofence_lifecycle CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_geofence_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE TABLE mine_geofence_version (
    id                        UUID             NOT NULL,
    geofence_id               UUID             NOT NULL,
    organization_id           UUID             NOT NULL,
    site_id                   UUID             NOT NULL,
    coordinate_system_id      UUID             NOT NULL,
    version_number            INTEGER          NOT NULL,
    name                      VARCHAR(128)     NOT NULL,
    geofence_type             VARCHAR(16)      NOT NULL,
    min_z                     DOUBLE PRECISION,
    max_z                     DOUBLE PRECISION,
    boundary_tolerance_meters DOUBLE PRECISION NOT NULL,
    enter_confirm_points      SMALLINT         NOT NULL,
    exit_confirm_points       SMALLINT         NOT NULL,
    dwell_seconds             INTEGER          NOT NULL,
    allowed_lateness_seconds  INTEGER          NOT NULL,
    idle_flush_seconds        INTEGER          NOT NULL,
    valid_from                TIMESTAMPTZ      NOT NULL,
    valid_to                  TIMESTAMPTZ,
    status                    VARCHAR(16)      NOT NULL DEFAULT 'DRAFT',
    content_hash              VARCHAR(71),
    published_at              TIMESTAMPTZ,
    published_by              UUID,
    created_at                TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                UUID,
    updated_at                TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                UUID,
    version                   BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_geofence_version PRIMARY KEY (id),
    CONSTRAINT uk_mine_geofence_version_number UNIQUE (geofence_id, version_number),
    CONSTRAINT uk_mine_geofence_version_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_geofence_version_geofence_scope
        FOREIGN KEY (geofence_id, organization_id, site_id)
        REFERENCES mine_geofence(id, organization_id, site_id),
    CONSTRAINT fk_mine_geofence_version_coordinate_scope
        FOREIGN KEY (coordinate_system_id, organization_id, site_id)
        REFERENCES mine_coordinate_system(id, organization_id, site_id),
    CONSTRAINT fk_mine_geofence_version_published_by
        FOREIGN KEY (published_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_geofence_version_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_geofence_version_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_geofence_version_number CHECK (version_number > 0),
    CONSTRAINT ck_mine_geofence_version_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_geofence_version_type CHECK (
        geofence_type IN ('RESTRICTED', 'WORK', 'LOAD', 'UNLOAD', 'WAIT', 'SAFETY', 'OTHER')
    ),
    CONSTRAINT ck_mine_geofence_version_height CHECK (
        (min_z IS NULL AND max_z IS NULL)
        OR (min_z IS NOT NULL AND max_z IS NOT NULL
            AND min_z < max_z
            AND min_z > '-Infinity'::DOUBLE PRECISION
            AND max_z < 'Infinity'::DOUBLE PRECISION)
    ),
    CONSTRAINT ck_mine_geofence_version_tuning CHECK (
        boundary_tolerance_meters >= 0 AND boundary_tolerance_meters <= 1000
        AND enter_confirm_points BETWEEN 1 AND 20
        AND exit_confirm_points BETWEEN 1 AND 20
        AND dwell_seconds BETWEEN 0 AND 86400
        AND allowed_lateness_seconds BETWEEN 0 AND 300
        AND idle_flush_seconds BETWEEN 1 AND 3600
        AND idle_flush_seconds > allowed_lateness_seconds
    ),
    CONSTRAINT ck_mine_geofence_version_validity
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mine_geofence_version_status
        CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_mine_geofence_version_publication CHECK (
        (status = 'DRAFT' AND content_hash IS NULL
            AND published_at IS NULL AND published_by IS NULL)
        OR (status = 'PUBLISHED'
            AND content_hash ~ '^sha256:[0-9a-f]{64}$'
            AND published_at IS NOT NULL AND published_at >= created_at)
    ),
    CONSTRAINT ck_mine_geofence_version_audit
        CHECK (updated_at >= created_at AND version >= 0),
    CONSTRAINT ex_mine_geofence_version_validity
        EXCLUDE USING gist (
            geofence_id WITH =,
            tstzrange(valid_from, valid_to, '[)') WITH &&
        ) WHERE (status = 'PUBLISHED')
);

CREATE TABLE mine_geofence_vertex (
    geofence_version_id UUID             NOT NULL,
    organization_id     UUID             NOT NULL,
    site_id             UUID             NOT NULL,
    sequence_number     INTEGER          NOT NULL,
    x                   DOUBLE PRECISION NOT NULL,
    y                   DOUBLE PRECISION NOT NULL,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_geofence_vertex
        PRIMARY KEY (geofence_version_id, sequence_number),
    CONSTRAINT fk_mine_geofence_vertex_version_scope
        FOREIGN KEY (geofence_version_id, organization_id, site_id)
        REFERENCES mine_geofence_version(id, organization_id, site_id),
    CONSTRAINT ck_mine_geofence_vertex_sequence
        CHECK (sequence_number BETWEEN 1 AND 10000),
    CONSTRAINT ck_mine_geofence_vertex_coordinate CHECK (
        x > '-Infinity'::DOUBLE PRECISION AND x < 'Infinity'::DOUBLE PRECISION
        AND y > '-Infinity'::DOUBLE PRECISION AND y < 'Infinity'::DOUBLE PRECISION
    )
);

CREATE INDEX idx_mine_geofence_site_status_code
    ON mine_geofence(site_id, status, geofence_code, id);
CREATE INDEX idx_mine_geofence_version_site_status_validity
    ON mine_geofence_version(site_id, status, valid_from DESC, id);

CREATE FUNCTION mine_require_unified_spatial_coordinate_system()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    coordinate_exists BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1
    FROM mine_coordinate_system
    WHERE id = NEW.coordinate_system_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id
      AND unified
          AND status = 'ACTIVE'
    ) INTO coordinate_exists;

    IF NOT coordinate_exists THEN
        RAISE EXCEPTION '空间版本必须使用本矿区活动统一坐标系'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_waypoint_coordinate_bounds()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    coordinate_row mine_coordinate_system%ROWTYPE;
BEGIN
    SELECT * INTO coordinate_row
    FROM mine_coordinate_system
    WHERE id = NEW.coordinate_system_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id;
    IF NOT FOUND OR NEW.x < coordinate_row.min_x OR NEW.x > coordinate_row.max_x
       OR NEW.y < coordinate_row.min_y OR NEW.y > coordinate_row.max_y
       OR (NEW.z IS NOT NULL AND coordinate_row.min_z IS NOT NULL
           AND (NEW.z < coordinate_row.min_z OR NEW.z > coordinate_row.max_z)) THEN
        RAISE EXCEPTION '途径点坐标超出统一坐标系边界'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_reject_published_spatial_version_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION '已发布空间版本不可修改或删除'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_reject_published_route_waypoint_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    candidate RECORD;
BEGIN
    candidate := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF EXISTS (
        SELECT 1 FROM mine_route_version
        WHERE id = candidate.route_version_id AND status = 'PUBLISHED'
    ) THEN
        RAISE EXCEPTION '已发布路线的途径点顺序不可修改'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_reject_published_geofence_vertex_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    candidate RECORD;
BEGIN
    candidate := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF EXISTS (
        SELECT 1 FROM mine_geofence_version
        WHERE id = candidate.geofence_version_id AND status = 'PUBLISHED'
    ) THEN
        RAISE EXCEPTION '已发布围栏的顶点不可修改'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_route_waypoint_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    route_coordinate UUID;
    waypoint_coordinate UUID;
BEGIN
    SELECT coordinate_system_id INTO route_coordinate
    FROM mine_route_version
    WHERE id = NEW.route_version_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id;
    SELECT coordinate_system_id INTO waypoint_coordinate
    FROM mine_waypoint_version
    WHERE id = NEW.waypoint_version_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id;
    IF route_coordinate IS NULL OR waypoint_coordinate IS NULL
       OR route_coordinate <> waypoint_coordinate THEN
        RAISE EXCEPTION '路线和途径点版本必须使用同一矿区统一坐标系'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_geofence_vertex_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    coordinate_row mine_coordinate_system%ROWTYPE;
BEGIN
    SELECT coordinate.* INTO coordinate_row
    FROM mine_geofence_version version
    JOIN mine_coordinate_system coordinate
      ON coordinate.id = version.coordinate_system_id
     AND coordinate.organization_id = version.organization_id
     AND coordinate.site_id = version.site_id
    WHERE version.id = NEW.geofence_version_id
      AND version.organization_id = NEW.organization_id
      AND version.site_id = NEW.site_id;
    IF NOT FOUND OR NEW.x < coordinate_row.min_x OR NEW.x > coordinate_row.max_x
       OR NEW.y < coordinate_row.min_y OR NEW.y > coordinate_row.max_y THEN
        RAISE EXCEPTION '围栏顶点超出本矿区统一坐标系边界'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_spatial_segments_intersect(
    ax DOUBLE PRECISION,
    ay DOUBLE PRECISION,
    bx DOUBLE PRECISION,
    by DOUBLE PRECISION,
    cx DOUBLE PRECISION,
    cy DOUBLE PRECISION,
    dx DOUBLE PRECISION,
    dy DOUBLE PRECISION
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    epsilon CONSTANT DOUBLE PRECISION := 0.000000001;
    d1 DOUBLE PRECISION;
    d2 DOUBLE PRECISION;
    d3 DOUBLE PRECISION;
    d4 DOUBLE PRECISION;
BEGIN
    d1 := (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    d2 := (bx - ax) * (dy - ay) - (by - ay) * (dx - ax);
    d3 := (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx);
    d4 := (dx - cx) * (by - cy) - (dy - cy) * (bx - cx);

    IF ((d1 > epsilon AND d2 < -epsilon) OR (d1 < -epsilon AND d2 > epsilon))
       AND ((d3 > epsilon AND d4 < -epsilon) OR (d3 < -epsilon AND d4 > epsilon)) THEN
        RETURN TRUE;
    END IF;
    IF abs(d1) <= epsilon AND cx BETWEEN LEAST(ax, bx) AND GREATEST(ax, bx)
       AND cy BETWEEN LEAST(ay, by) AND GREATEST(ay, by) THEN RETURN TRUE; END IF;
    IF abs(d2) <= epsilon AND dx BETWEEN LEAST(ax, bx) AND GREATEST(ax, bx)
       AND dy BETWEEN LEAST(ay, by) AND GREATEST(ay, by) THEN RETURN TRUE; END IF;
    IF abs(d3) <= epsilon AND ax BETWEEN LEAST(cx, dx) AND GREATEST(cx, dx)
       AND ay BETWEEN LEAST(cy, dy) AND GREATEST(cy, dy) THEN RETURN TRUE; END IF;
    IF abs(d4) <= epsilon AND bx BETWEEN LEAST(cx, dx) AND GREATEST(cx, dx)
       AND by BETWEEN LEAST(cy, dy) AND GREATEST(cy, dy) THEN RETURN TRUE; END IF;
    RETURN FALSE;
END
$$;

CREATE FUNCTION mine_validate_spatial_version_publish()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    item_count INTEGER;
    invalid_count INTEGER;
    polygon_area DOUBLE PRECISION;
    self_intersects BOOLEAN;
BEGIN
    IF NEW.status <> 'PUBLISHED' OR OLD.status = 'PUBLISHED' THEN
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'mine_route_version' THEN
        SELECT COUNT(*), COUNT(*) FILTER (
            WHERE waypoint.status <> 'PUBLISHED'
               OR waypoint.coordinate_system_id <> NEW.coordinate_system_id
               OR waypoint.valid_from > NEW.valid_from
               OR (NEW.valid_to IS NULL AND waypoint.valid_to IS NOT NULL)
               OR (NEW.valid_to IS NOT NULL
                   AND waypoint.valid_to IS NOT NULL
                   AND waypoint.valid_to < NEW.valid_to)
        )
        INTO item_count, invalid_count
        FROM mine_route_version_waypoint route_waypoint
        JOIN mine_waypoint_version waypoint
          ON waypoint.id = route_waypoint.waypoint_version_id
         AND waypoint.organization_id = route_waypoint.organization_id
         AND waypoint.site_id = route_waypoint.site_id
        WHERE route_waypoint.route_version_id = NEW.id;
        IF item_count < 2 OR invalid_count > 0 OR NOT EXISTS (
            SELECT 1
            FROM mine_route_version_waypoint
            WHERE route_version_id = NEW.id
            GROUP BY route_version_id
            HAVING MIN(sequence_number) = 1
               AND MAX(sequence_number) = COUNT(*)
        ) THEN
            RAISE EXCEPTION '路线发布需要至少两个连续且有效的已发布途径点版本'
                USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'mine_geofence_version' THEN
        SELECT COUNT(*), COUNT(DISTINCT (x, y))
        INTO item_count, invalid_count
        FROM mine_geofence_vertex
        WHERE geofence_version_id = NEW.id;
        IF item_count < 3 OR invalid_count <> item_count OR NOT EXISTS (
            SELECT 1
            FROM mine_geofence_vertex
            WHERE geofence_version_id = NEW.id
            GROUP BY geofence_version_id
            HAVING MIN(sequence_number) = 1
               AND MAX(sequence_number) = COUNT(*)
        ) THEN
            RAISE EXCEPTION '围栏发布需要至少三个不重复且顺序连续的顶点'
                USING ERRCODE = '23514';
        END IF;

        WITH numbered AS (
            SELECT sequence_number, x, y,
                   ROW_NUMBER() OVER (ORDER BY sequence_number) AS row_number,
                   COUNT(*) OVER () AS vertex_count
            FROM mine_geofence_vertex
            WHERE geofence_version_id = NEW.id
        ), edges AS (
            SELECT current_point.row_number AS edge_number,
                   current_point.vertex_count,
                   current_point.x AS ax, current_point.y AS ay,
                   next_point.x AS bx, next_point.y AS by
            FROM numbered current_point
            JOIN numbered next_point
              ON next_point.row_number = CASE
                    WHEN current_point.row_number = current_point.vertex_count THEN 1
                    ELSE current_point.row_number + 1
                 END
        )
        SELECT abs(SUM(ax * by - bx * ay)) / 2.0
        INTO polygon_area
        FROM edges;
        IF polygon_area <= 0.01 THEN
            RAISE EXCEPTION '围栏多边形面积必须大于 0.01 平方米'
                USING ERRCODE = '23514';
        END IF;

        WITH numbered AS (
            SELECT sequence_number, x, y,
                   ROW_NUMBER() OVER (ORDER BY sequence_number) AS row_number,
                   COUNT(*) OVER () AS vertex_count
            FROM mine_geofence_vertex
            WHERE geofence_version_id = NEW.id
        ), edges AS (
            SELECT current_point.row_number AS edge_number,
                   current_point.vertex_count,
                   current_point.x AS ax, current_point.y AS ay,
                   next_point.x AS bx, next_point.y AS by
            FROM numbered current_point
            JOIN numbered next_point
              ON next_point.row_number = CASE
                    WHEN current_point.row_number = current_point.vertex_count THEN 1
                    ELSE current_point.row_number + 1
                 END
        )
        SELECT EXISTS (
            SELECT 1
            FROM edges first_edge
            JOIN edges second_edge ON first_edge.edge_number < second_edge.edge_number
            WHERE second_edge.edge_number <> first_edge.edge_number + 1
              AND NOT (first_edge.edge_number = 1
                       AND second_edge.edge_number = first_edge.vertex_count)
              AND mine_spatial_segments_intersect(
                    first_edge.ax, first_edge.ay, first_edge.bx, first_edge.by,
                    second_edge.ax, second_edge.ay, second_edge.bx, second_edge.by)
        ) INTO self_intersects;
        IF self_intersects THEN
            RAISE EXCEPTION '围栏多边形不得自相交'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mine_waypoint_version_coordinate
BEFORE INSERT OR UPDATE OF coordinate_system_id, organization_id, site_id, x, y, z
ON mine_waypoint_version
FOR EACH ROW EXECUTE FUNCTION mine_require_unified_spatial_coordinate_system();

CREATE TRIGGER trg_mine_waypoint_version_bounds
BEFORE INSERT OR UPDATE OF coordinate_system_id, organization_id, site_id, x, y, z
ON mine_waypoint_version
FOR EACH ROW EXECUTE FUNCTION mine_validate_waypoint_coordinate_bounds();

CREATE TRIGGER trg_mine_route_version_coordinate
BEFORE INSERT OR UPDATE OF coordinate_system_id, organization_id, site_id
ON mine_route_version
FOR EACH ROW EXECUTE FUNCTION mine_require_unified_spatial_coordinate_system();

CREATE TRIGGER trg_mine_geofence_version_coordinate
BEFORE INSERT OR UPDATE OF coordinate_system_id, organization_id, site_id
ON mine_geofence_version
FOR EACH ROW EXECUTE FUNCTION mine_require_unified_spatial_coordinate_system();

CREATE TRIGGER trg_mine_waypoint_version_publish_validation
BEFORE UPDATE OF status ON mine_waypoint_version
FOR EACH ROW EXECUTE FUNCTION mine_validate_spatial_version_publish();

CREATE TRIGGER trg_mine_route_version_publish_validation
BEFORE UPDATE OF status ON mine_route_version
FOR EACH ROW EXECUTE FUNCTION mine_validate_spatial_version_publish();

CREATE TRIGGER trg_mine_geofence_version_publish_validation
BEFORE UPDATE OF status ON mine_geofence_version
FOR EACH ROW EXECUTE FUNCTION mine_validate_spatial_version_publish();

CREATE TRIGGER trg_mine_waypoint_version_immutable
BEFORE UPDATE OR DELETE ON mine_waypoint_version
FOR EACH ROW EXECUTE FUNCTION mine_reject_published_spatial_version_change();

CREATE TRIGGER trg_mine_route_version_immutable
BEFORE UPDATE OR DELETE ON mine_route_version
FOR EACH ROW EXECUTE FUNCTION mine_reject_published_spatial_version_change();

CREATE TRIGGER trg_mine_geofence_version_immutable
BEFORE UPDATE OR DELETE ON mine_geofence_version
FOR EACH ROW EXECUTE FUNCTION mine_reject_published_spatial_version_change();

CREATE TRIGGER trg_mine_route_waypoint_scope
BEFORE INSERT OR UPDATE ON mine_route_version_waypoint
FOR EACH ROW EXECUTE FUNCTION mine_validate_route_waypoint_scope();

CREATE TRIGGER trg_mine_route_waypoint_immutable
BEFORE INSERT OR UPDATE OR DELETE ON mine_route_version_waypoint
FOR EACH ROW EXECUTE FUNCTION mine_reject_published_route_waypoint_change();

CREATE TRIGGER trg_mine_geofence_vertex_scope
BEFORE INSERT OR UPDATE ON mine_geofence_vertex
FOR EACH ROW EXECUTE FUNCTION mine_validate_geofence_vertex_scope();

CREATE TRIGGER trg_mine_geofence_vertex_immutable
BEFORE INSERT OR UPDATE OR DELETE ON mine_geofence_vertex
FOR EACH ROW EXECUTE FUNCTION mine_reject_published_geofence_vertex_change();

COMMENT ON TABLE mine_waypoint IS '矿区途径点稳定业务标识';
COMMENT ON COLUMN mine_waypoint.id IS '途径点 UUIDv7 标识';
COMMENT ON COLUMN mine_waypoint.organization_id IS '途径点所属组织标识';
COMMENT ON COLUMN mine_waypoint.site_id IS '途径点所属矿区标识';
COMMENT ON COLUMN mine_waypoint.waypoint_code IS '矿区内稳定唯一的途径点编码';
COMMENT ON COLUMN mine_waypoint.status IS '途径点生命周期状态';
COMMENT ON COLUMN mine_waypoint.created_at IS '途径点创建时间';
COMMENT ON COLUMN mine_waypoint.created_by IS '途径点创建用户标识';
COMMENT ON COLUMN mine_waypoint.updated_at IS '途径点最近更新时间';
COMMENT ON COLUMN mine_waypoint.updated_by IS '途径点最近更新用户标识';
COMMENT ON COLUMN mine_waypoint.archived_at IS '途径点归档时间';
COMMENT ON COLUMN mine_waypoint.version IS '途径点乐观锁版本';

COMMENT ON TABLE mine_waypoint_version IS '途径点可编辑草稿和不可变发布版本';
COMMENT ON COLUMN mine_waypoint_version.id IS '途径点版本 UUIDv7 标识';
COMMENT ON COLUMN mine_waypoint_version.waypoint_id IS '稳定途径点标识';
COMMENT ON COLUMN mine_waypoint_version.organization_id IS '途径点版本所属组织标识';
COMMENT ON COLUMN mine_waypoint_version.site_id IS '途径点版本所属矿区标识';
COMMENT ON COLUMN mine_waypoint_version.coordinate_system_id IS '途径点使用的矿区统一坐标系标识';
COMMENT ON COLUMN mine_waypoint_version.version_number IS '途径点业务版本号';
COMMENT ON COLUMN mine_waypoint_version.name IS '途径点版本名称';
COMMENT ON COLUMN mine_waypoint_version.waypoint_type IS '装载卸载检查等待或出入口类型';
COMMENT ON COLUMN mine_waypoint_version.x IS '途径点圆心统一坐标 X（米）';
COMMENT ON COLUMN mine_waypoint_version.y IS '途径点圆心统一坐标 Y（米）';
COMMENT ON COLUMN mine_waypoint_version.z IS '途径点圆心统一坐标 Z（米）';
COMMENT ON COLUMN mine_waypoint_version.radius_meters IS '途径点判定圆形半径（米）';
COMMENT ON COLUMN mine_waypoint_version.min_z IS '途径点判定最小高度（米）';
COMMENT ON COLUMN mine_waypoint_version.max_z IS '途径点判定最大高度（米）';
COMMENT ON COLUMN mine_waypoint_version.boundary_tolerance_meters IS '边界保持原稳定状态的容差（米）';
COMMENT ON COLUMN mine_waypoint_version.enter_confirm_points IS '确认进入所需连续位置点数';
COMMENT ON COLUMN mine_waypoint_version.exit_confirm_points IS '确认离开所需连续位置点数';
COMMENT ON COLUMN mine_waypoint_version.dwell_seconds IS '进入后触发单次停留事件的秒数';
COMMENT ON COLUMN mine_waypoint_version.allowed_lateness_seconds IS '允许事件时间乱序的秒数';
COMMENT ON COLUMN mine_waypoint_version.idle_flush_seconds IS '无新位置后排空迟到缓冲的秒数';
COMMENT ON COLUMN mine_waypoint_version.valid_from IS '版本生效起始时间（含）';
COMMENT ON COLUMN mine_waypoint_version.valid_to IS '版本生效结束时间（不含）';
COMMENT ON COLUMN mine_waypoint_version.status IS '草稿或已发布状态';
COMMENT ON COLUMN mine_waypoint_version.content_hash IS '发布内容规范化 SHA-256 摘要';
COMMENT ON COLUMN mine_waypoint_version.published_at IS '版本发布时间';
COMMENT ON COLUMN mine_waypoint_version.published_by IS '版本发布用户标识';
COMMENT ON COLUMN mine_waypoint_version.created_at IS '版本创建时间';
COMMENT ON COLUMN mine_waypoint_version.created_by IS '版本创建用户标识';
COMMENT ON COLUMN mine_waypoint_version.updated_at IS '草稿最近更新时间';
COMMENT ON COLUMN mine_waypoint_version.updated_by IS '草稿最近更新用户标识';
COMMENT ON COLUMN mine_waypoint_version.version IS '草稿乐观锁版本';

COMMENT ON TABLE mine_route IS '矿区路线稳定业务标识';
COMMENT ON COLUMN mine_route.id IS '路线 UUIDv7 标识';
COMMENT ON COLUMN mine_route.organization_id IS '路线所属组织标识';
COMMENT ON COLUMN mine_route.site_id IS '路线所属矿区标识';
COMMENT ON COLUMN mine_route.route_code IS '矿区内稳定唯一的路线编码';
COMMENT ON COLUMN mine_route.status IS '路线生命周期状态';
COMMENT ON COLUMN mine_route.created_at IS '路线创建时间';
COMMENT ON COLUMN mine_route.created_by IS '路线创建用户标识';
COMMENT ON COLUMN mine_route.updated_at IS '路线最近更新时间';
COMMENT ON COLUMN mine_route.updated_by IS '路线最近更新用户标识';
COMMENT ON COLUMN mine_route.archived_at IS '路线归档时间';
COMMENT ON COLUMN mine_route.version IS '路线乐观锁版本';

COMMENT ON TABLE mine_route_version IS '路线可编辑草稿和不可变发布版本';
COMMENT ON COLUMN mine_route_version.id IS '路线版本 UUIDv7 标识';
COMMENT ON COLUMN mine_route_version.route_id IS '稳定路线标识';
COMMENT ON COLUMN mine_route_version.organization_id IS '路线版本所属组织标识';
COMMENT ON COLUMN mine_route_version.site_id IS '路线版本所属矿区标识';
COMMENT ON COLUMN mine_route_version.coordinate_system_id IS '路线使用的矿区统一坐标系标识';
COMMENT ON COLUMN mine_route_version.version_number IS '路线业务版本号';
COMMENT ON COLUMN mine_route_version.name IS '路线版本名称';
COMMENT ON COLUMN mine_route_version.direction IS '正向反向或双向通行方向';
COMMENT ON COLUMN mine_route_version.applicable_vehicle_types IS '适用车辆类型固定枚举数组';
COMMENT ON COLUMN mine_route_version.valid_from IS '路线版本生效起始时间（含）';
COMMENT ON COLUMN mine_route_version.valid_to IS '路线版本生效结束时间（不含）';
COMMENT ON COLUMN mine_route_version.status IS '草稿或已发布状态';
COMMENT ON COLUMN mine_route_version.content_hash IS '发布内容规范化 SHA-256 摘要';
COMMENT ON COLUMN mine_route_version.published_at IS '版本发布时间';
COMMENT ON COLUMN mine_route_version.published_by IS '版本发布用户标识';
COMMENT ON COLUMN mine_route_version.created_at IS '版本创建时间';
COMMENT ON COLUMN mine_route_version.created_by IS '版本创建用户标识';
COMMENT ON COLUMN mine_route_version.updated_at IS '草稿最近更新时间';
COMMENT ON COLUMN mine_route_version.updated_by IS '草稿最近更新用户标识';
COMMENT ON COLUMN mine_route_version.version IS '草稿乐观锁版本';

COMMENT ON TABLE mine_route_version_waypoint IS '路线版本内按顺序引用的途径点发布版本';
COMMENT ON COLUMN mine_route_version_waypoint.route_version_id IS '所属路线版本标识';
COMMENT ON COLUMN mine_route_version_waypoint.waypoint_version_id IS '引用的途径点版本标识';
COMMENT ON COLUMN mine_route_version_waypoint.organization_id IS '路线途径点所属组织标识';
COMMENT ON COLUMN mine_route_version_waypoint.site_id IS '路线途径点所属矿区标识';
COMMENT ON COLUMN mine_route_version_waypoint.sequence_number IS '途径点在路线中的一基顺序号';
COMMENT ON COLUMN mine_route_version_waypoint.stop_required IS '经过该点时是否要求停车';
COMMENT ON COLUMN mine_route_version_waypoint.expected_dwell_seconds IS '计划停留秒数';
COMMENT ON COLUMN mine_route_version_waypoint.created_at IS '路线途径点记录创建时间';

COMMENT ON TABLE mine_geofence IS '矿区电子围栏稳定业务标识';
COMMENT ON COLUMN mine_geofence.id IS '电子围栏 UUIDv7 标识';
COMMENT ON COLUMN mine_geofence.organization_id IS '电子围栏所属组织标识';
COMMENT ON COLUMN mine_geofence.site_id IS '电子围栏所属矿区标识';
COMMENT ON COLUMN mine_geofence.geofence_code IS '矿区内稳定唯一的电子围栏编码';
COMMENT ON COLUMN mine_geofence.status IS '电子围栏生命周期状态';
COMMENT ON COLUMN mine_geofence.created_at IS '电子围栏创建时间';
COMMENT ON COLUMN mine_geofence.created_by IS '电子围栏创建用户标识';
COMMENT ON COLUMN mine_geofence.updated_at IS '电子围栏最近更新时间';
COMMENT ON COLUMN mine_geofence.updated_by IS '电子围栏最近更新用户标识';
COMMENT ON COLUMN mine_geofence.archived_at IS '电子围栏归档时间';
COMMENT ON COLUMN mine_geofence.version IS '电子围栏乐观锁版本';

COMMENT ON TABLE mine_geofence_version IS '电子围栏可编辑草稿和不可变发布版本';
COMMENT ON COLUMN mine_geofence_version.id IS '电子围栏版本 UUIDv7 标识';
COMMENT ON COLUMN mine_geofence_version.geofence_id IS '稳定电子围栏标识';
COMMENT ON COLUMN mine_geofence_version.organization_id IS '围栏版本所属组织标识';
COMMENT ON COLUMN mine_geofence_version.site_id IS '围栏版本所属矿区标识';
COMMENT ON COLUMN mine_geofence_version.coordinate_system_id IS '围栏使用的矿区统一坐标系标识';
COMMENT ON COLUMN mine_geofence_version.version_number IS '围栏业务版本号';
COMMENT ON COLUMN mine_geofence_version.name IS '围栏版本名称';
COMMENT ON COLUMN mine_geofence_version.geofence_type IS '限制作业装卸等待安全或其他围栏类型';
COMMENT ON COLUMN mine_geofence_version.min_z IS '围栏判定最小高度（米）';
COMMENT ON COLUMN mine_geofence_version.max_z IS '围栏判定最大高度（米）';
COMMENT ON COLUMN mine_geofence_version.boundary_tolerance_meters IS '边界保持原稳定状态的容差（米）';
COMMENT ON COLUMN mine_geofence_version.enter_confirm_points IS '确认进入所需连续位置点数';
COMMENT ON COLUMN mine_geofence_version.exit_confirm_points IS '确认离开所需连续位置点数';
COMMENT ON COLUMN mine_geofence_version.dwell_seconds IS '进入后触发单次停留事件的秒数';
COMMENT ON COLUMN mine_geofence_version.allowed_lateness_seconds IS '允许事件时间乱序的秒数';
COMMENT ON COLUMN mine_geofence_version.idle_flush_seconds IS '无新位置后排空迟到缓冲的秒数';
COMMENT ON COLUMN mine_geofence_version.valid_from IS '围栏版本生效起始时间（含）';
COMMENT ON COLUMN mine_geofence_version.valid_to IS '围栏版本生效结束时间（不含）';
COMMENT ON COLUMN mine_geofence_version.status IS '草稿或已发布状态';
COMMENT ON COLUMN mine_geofence_version.content_hash IS '发布内容规范化 SHA-256 摘要';
COMMENT ON COLUMN mine_geofence_version.published_at IS '版本发布时间';
COMMENT ON COLUMN mine_geofence_version.published_by IS '版本发布用户标识';
COMMENT ON COLUMN mine_geofence_version.created_at IS '版本创建时间';
COMMENT ON COLUMN mine_geofence_version.created_by IS '版本创建用户标识';
COMMENT ON COLUMN mine_geofence_version.updated_at IS '草稿最近更新时间';
COMMENT ON COLUMN mine_geofence_version.updated_by IS '草稿最近更新用户标识';
COMMENT ON COLUMN mine_geofence_version.version IS '草稿乐观锁版本';

COMMENT ON TABLE mine_geofence_vertex IS '电子围栏发布几何的有序二维顶点';
COMMENT ON COLUMN mine_geofence_vertex.geofence_version_id IS '所属电子围栏版本标识';
COMMENT ON COLUMN mine_geofence_vertex.organization_id IS '围栏顶点所属组织标识';
COMMENT ON COLUMN mine_geofence_vertex.site_id IS '围栏顶点所属矿区标识';
COMMENT ON COLUMN mine_geofence_vertex.sequence_number IS '顶点在多边形中的一基顺序号';
COMMENT ON COLUMN mine_geofence_vertex.x IS '顶点统一坐标 X（米）';
COMMENT ON COLUMN mine_geofence_vertex.y IS '顶点统一坐标 Y（米）';
COMMENT ON COLUMN mine_geofence_vertex.created_at IS '围栏顶点记录创建时间';

COMMENT ON FUNCTION mine_is_vehicle_type_array(JSONB) IS '校验路线适用车辆类型为固定非空枚举数组';
COMMENT ON FUNCTION mine_require_unified_spatial_coordinate_system() IS '强制空间版本使用本矿区活动统一坐标系';
COMMENT ON FUNCTION mine_validate_waypoint_coordinate_bounds() IS '校验途径点位于矿区统一坐标边界内';
COMMENT ON FUNCTION mine_reject_published_spatial_version_change() IS '阻止已发布空间版本被修改或删除';
COMMENT ON FUNCTION mine_reject_published_route_waypoint_change() IS '阻止已发布路线的途径点顺序被修改';
COMMENT ON FUNCTION mine_reject_published_geofence_vertex_change() IS '阻止已发布围栏的顶点被修改';
COMMENT ON FUNCTION mine_validate_route_waypoint_scope() IS '校验路线与途径点版本属于同一矿区统一坐标系';
COMMENT ON FUNCTION mine_validate_geofence_vertex_scope() IS '校验围栏顶点位于本矿区统一坐标边界内';
COMMENT ON FUNCTION mine_spatial_segments_intersect(
    DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION,
    DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION
) IS '确定性判定两个二维闭合线段是否相交';
COMMENT ON FUNCTION mine_validate_spatial_version_publish() IS '发布前校验路线引用和围栏拓扑完整性';
