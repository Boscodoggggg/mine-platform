-- 建立班次、作业单、运输执行事实、人工修正和可靠业务消息控制面。

CREATE TABLE mine_shift_definition (
    id                UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    site_id           UUID         NOT NULL,
    definition_code   VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    start_local       TIME         NOT NULL,
    end_local         TIME         NOT NULL,
    crosses_midnight  BOOLEAN      NOT NULL DEFAULT FALSE,
    valid_from        DATE         NOT NULL,
    valid_to          DATE,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        UUID,
    archived_at       TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_shift_definition PRIMARY KEY (id),
    CONSTRAINT uk_mine_shift_definition_site_code
        UNIQUE (site_id, definition_code),
    CONSTRAINT uk_mine_shift_definition_scope
        UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_shift_definition_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_shift_definition_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_shift_definition_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_shift_definition_code
        CHECK (definition_code ~ '^[a-z][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_mine_shift_definition_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_mine_shift_definition_local_window CHECK (
        (NOT crosses_midnight AND end_local > start_local)
        OR (crosses_midnight AND end_local < start_local)
    ),
    CONSTRAINT ck_mine_shift_definition_validity
        CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_mine_shift_definition_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mine_shift_definition_lifecycle CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL
            AND archived_at >= created_at)
    ),
    CONSTRAINT ck_mine_shift_definition_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_shift_definition_site_status_code
    ON mine_shift_definition(site_id, status, definition_code, id);

CREATE TABLE mine_shift_instance (
    id                  UUID        NOT NULL,
    organization_id     UUID        NOT NULL,
    site_id             UUID        NOT NULL,
    shift_definition_id UUID        NOT NULL,
    business_date       DATE        NOT NULL,
    planned_start_at    TIMESTAMPTZ NOT NULL,
    planned_end_at      TIMESTAMPTZ NOT NULL,
    actual_start_at     TIMESTAMPTZ,
    actual_end_at       TIMESTAMPTZ,
    supervisor_person_id UUID,
    definition_snapshot JSONB       NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    opened_at           TIMESTAMPTZ,
    opened_by           UUID,
    closed_at           TIMESTAMPTZ,
    closed_by           UUID,
    cancelled_at        TIMESTAMPTZ,
    cancelled_by        UUID,
    cancellation_reason VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_shift_instance PRIMARY KEY (id),
    CONSTRAINT uk_mine_shift_instance_business_date
        UNIQUE (organization_id, site_id, shift_definition_id, business_date),
    CONSTRAINT uk_mine_shift_instance_scope
        UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_shift_instance_definition_scope
        FOREIGN KEY (shift_definition_id, organization_id, site_id)
        REFERENCES mine_shift_definition(id, organization_id, site_id),
    CONSTRAINT fk_mine_shift_instance_supervisor_organization
        FOREIGN KEY (supervisor_person_id, organization_id)
        REFERENCES mine_person(id, organization_id),
    CONSTRAINT fk_mine_shift_instance_opened_by
        FOREIGN KEY (opened_by) REFERENCES sys_user(id),
    CONSTRAINT fk_mine_shift_instance_closed_by
        FOREIGN KEY (closed_by) REFERENCES sys_user(id),
    CONSTRAINT fk_mine_shift_instance_cancelled_by
        FOREIGN KEY (cancelled_by) REFERENCES sys_user(id),
    CONSTRAINT fk_mine_shift_instance_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_shift_instance_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_shift_instance_planned_window CHECK (
        planned_end_at > planned_start_at
        AND planned_end_at <= planned_start_at + INTERVAL '24 hours'
    ),
    CONSTRAINT ck_mine_shift_instance_actual_window CHECK (
        actual_end_at IS NULL
        OR (actual_start_at IS NOT NULL AND actual_end_at >= actual_start_at)
    ),
    CONSTRAINT ck_mine_shift_instance_snapshot CHECK (
        jsonb_typeof(definition_snapshot) = 'object'
        AND octet_length(definition_snapshot::TEXT) <= 32768
    ),
    CONSTRAINT ck_mine_shift_instance_status
        CHECK (status IN ('PLANNED', 'OPEN', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_mine_shift_instance_action_actor CHECK (
        (opened_at IS NULL) = (opened_by IS NULL)
        AND (closed_at IS NULL) = (closed_by IS NULL)
        AND (cancelled_at IS NULL) = (cancelled_by IS NULL)
    ),
    CONSTRAINT ck_mine_shift_instance_action_timeline CHECK (
        (opened_at IS NULL OR opened_at >= created_at)
        AND (closed_at IS NULL
            OR (opened_at IS NOT NULL AND closed_at >= opened_at))
        AND (cancelled_at IS NULL OR cancelled_at >= created_at)
    ),
    CONSTRAINT ck_mine_shift_instance_lifecycle CHECK (
        (status = 'PLANNED'
            AND actual_start_at IS NULL AND actual_end_at IS NULL
            AND opened_at IS NULL AND opened_by IS NULL
            AND closed_at IS NULL AND closed_by IS NULL
            AND cancelled_at IS NULL AND cancelled_by IS NULL
            AND cancellation_reason IS NULL)
        OR (status = 'OPEN'
            AND actual_start_at IS NOT NULL AND actual_end_at IS NULL
            AND opened_at IS NOT NULL
            AND closed_at IS NULL AND closed_by IS NULL
            AND cancelled_at IS NULL AND cancelled_by IS NULL
            AND cancellation_reason IS NULL)
        OR (status = 'CLOSED'
            AND actual_start_at IS NOT NULL AND actual_end_at IS NOT NULL
            AND opened_at IS NOT NULL AND closed_at IS NOT NULL
            AND closed_at >= opened_at
            AND cancelled_at IS NULL AND cancelled_by IS NULL
            AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED'
            AND actual_start_at IS NULL AND actual_end_at IS NULL
            AND opened_at IS NULL AND opened_by IS NULL
            AND closed_at IS NULL AND closed_by IS NULL
            AND cancelled_at IS NOT NULL
            AND cancellation_reason IS NOT NULL
            AND btrim(cancellation_reason) <> '')
    ),
    CONSTRAINT ck_mine_shift_instance_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_shift_instance_site_business_status
    ON mine_shift_instance(site_id, business_date DESC, status, planned_start_at, id);
CREATE INDEX idx_mine_shift_instance_open
    ON mine_shift_instance(site_id, planned_start_at, planned_end_at, id)
    WHERE status = 'OPEN';

CREATE TABLE mine_work_order (
    id                        UUID          NOT NULL,
    organization_id           UUID          NOT NULL,
    site_id                   UUID          NOT NULL,
    shift_instance_id         UUID          NOT NULL,
    order_number              VARCHAR(64)   NOT NULL,
    title                     VARCHAR(128)  NOT NULL,
    work_type                 VARCHAR(24)   NOT NULL,
    planned_start_at          TIMESTAMPTZ   NOT NULL,
    planned_end_at            TIMESTAMPTZ   NOT NULL,
    planned_cycles            INTEGER,
    planned_tons              NUMERIC(14,3),
    priority                  SMALLINT      NOT NULL DEFAULT 50,
    route_version_id          UUID,
    load_geofence_version_id  UUID,
    unload_geofence_version_id UUID,
    release_snapshot          JSONB,
    status                    VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    released_at               TIMESTAMPTZ,
    released_by               UUID,
    started_at                TIMESTAMPTZ,
    started_by                UUID,
    paused_at                 TIMESTAMPTZ,
    paused_by                 UUID,
    completed_at              TIMESTAMPTZ,
    completed_by              UUID,
    cancelled_at              TIMESTAMPTZ,
    cancelled_by              UUID,
    aborted_at                TIMESTAMPTZ,
    aborted_by                UUID,
    terminal_reason           VARCHAR(500),
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                UUID,
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                UUID,
    version                   BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_work_order PRIMARY KEY (id),
    CONSTRAINT uk_mine_work_order_site_number UNIQUE (site_id, order_number),
    CONSTRAINT uk_mine_work_order_scope UNIQUE (id, organization_id, site_id),
    CONSTRAINT uk_mine_work_order_shift_context
        UNIQUE (id, shift_instance_id, organization_id, site_id),
    CONSTRAINT fk_mine_work_order_shift_scope
        FOREIGN KEY (shift_instance_id, organization_id, site_id)
        REFERENCES mine_shift_instance(id, organization_id, site_id),
    CONSTRAINT fk_mine_work_order_route_scope
        FOREIGN KEY (route_version_id, organization_id, site_id)
        REFERENCES mine_route_version(id, organization_id, site_id),
    CONSTRAINT fk_mine_work_order_load_geofence_scope
        FOREIGN KEY (load_geofence_version_id, organization_id, site_id)
        REFERENCES mine_geofence_version(id, organization_id, site_id),
    CONSTRAINT fk_mine_work_order_unload_geofence_scope
        FOREIGN KEY (unload_geofence_version_id, organization_id, site_id)
        REFERENCES mine_geofence_version(id, organization_id, site_id),
    CONSTRAINT fk_mine_work_order_released_by
        FOREIGN KEY (released_by) REFERENCES sys_user(id),
    CONSTRAINT fk_mine_work_order_started_by
        FOREIGN KEY (started_by) REFERENCES sys_user(id),
    CONSTRAINT fk_mine_work_order_paused_by
        FOREIGN KEY (paused_by) REFERENCES sys_user(id),
    CONSTRAINT fk_mine_work_order_completed_by
        FOREIGN KEY (completed_by) REFERENCES sys_user(id),
    CONSTRAINT fk_mine_work_order_cancelled_by
        FOREIGN KEY (cancelled_by) REFERENCES sys_user(id),
    CONSTRAINT fk_mine_work_order_aborted_by
        FOREIGN KEY (aborted_by) REFERENCES sys_user(id),
    CONSTRAINT fk_mine_work_order_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_work_order_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_work_order_number
        CHECK (order_number ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}$'),
    CONSTRAINT ck_mine_work_order_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_mine_work_order_type CHECK (work_type IN ('HAULAGE')),
    CONSTRAINT ck_mine_work_order_window
        CHECK (planned_end_at > planned_start_at),
    CONSTRAINT ck_mine_work_order_plan CHECK (
        (planned_cycles IS NULL OR planned_cycles BETWEEN 1 AND 1000000)
        AND (planned_tons IS NULL OR planned_tons > 0)
        AND priority BETWEEN 1 AND 100
    ),
    CONSTRAINT ck_mine_work_order_status CHECK (
        status IN ('DRAFT', 'RELEASED', 'RUNNING', 'PAUSED',
                   'COMPLETED', 'CANCELLED', 'ABORTED')
    ),
    CONSTRAINT ck_mine_work_order_release_snapshot CHECK (
        (released_at IS NULL AND released_by IS NULL
            AND route_version_id IS NULL
            AND load_geofence_version_id IS NULL
            AND unload_geofence_version_id IS NULL
            AND release_snapshot IS NULL)
        OR (released_at IS NOT NULL
            AND route_version_id IS NOT NULL
            AND load_geofence_version_id IS NOT NULL
            AND unload_geofence_version_id IS NOT NULL
            AND load_geofence_version_id <> unload_geofence_version_id
            AND release_snapshot IS NOT NULL
            AND release_snapshot ?& ARRAY[
                'orderNumber', 'title', 'workType',
                'plannedStartAt', 'plannedEndAt',
                'routeVersionId', 'loadGeofenceVersionId',
                'unloadGeofenceVersionId', 'routeName',
                'loadZoneName', 'unloadZoneName'
            ]
            AND release_snapshot ->> 'routeVersionId' = route_version_id::TEXT
            AND release_snapshot ->> 'loadGeofenceVersionId'
                = load_geofence_version_id::TEXT
            AND release_snapshot ->> 'unloadGeofenceVersionId'
                = unload_geofence_version_id::TEXT
            AND jsonb_typeof(release_snapshot) = 'object'
            AND octet_length(release_snapshot::TEXT) <= 65536)
    ),
    CONSTRAINT ck_mine_work_order_action_actor CHECK (
        (released_at IS NULL) = (released_by IS NULL)
        AND (started_at IS NULL) = (started_by IS NULL)
        AND (paused_at IS NULL) = (paused_by IS NULL)
        AND (completed_at IS NULL) = (completed_by IS NULL)
        AND (cancelled_at IS NULL) = (cancelled_by IS NULL)
        AND (aborted_at IS NULL) = (aborted_by IS NULL)
    ),
    CONSTRAINT ck_mine_work_order_action_timeline CHECK (
        (released_at IS NULL OR released_at >= created_at)
        AND (started_at IS NULL
            OR (released_at IS NOT NULL AND started_at >= released_at))
        AND (paused_at IS NULL
            OR (started_at IS NOT NULL AND paused_at >= started_at))
        AND (completed_at IS NULL
            OR (started_at IS NOT NULL AND completed_at >= started_at))
        AND (cancelled_at IS NULL
            OR (cancelled_at >= created_at
                AND (released_at IS NULL OR cancelled_at >= released_at)))
        AND (aborted_at IS NULL
            OR (started_at IS NOT NULL AND aborted_at >= started_at))
    ),
    CONSTRAINT ck_mine_work_order_lifecycle CHECK (
        (status = 'DRAFT' AND released_at IS NULL
            AND started_at IS NULL AND paused_at IS NULL
            AND completed_at IS NULL AND cancelled_at IS NULL AND aborted_at IS NULL
            AND terminal_reason IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL
            AND started_at IS NULL AND paused_at IS NULL
            AND completed_at IS NULL AND cancelled_at IS NULL AND aborted_at IS NULL
            AND terminal_reason IS NULL)
        OR (status = 'RUNNING' AND released_at IS NOT NULL AND started_at IS NOT NULL
            AND completed_at IS NULL AND cancelled_at IS NULL AND aborted_at IS NULL
            AND terminal_reason IS NULL)
        OR (status = 'PAUSED' AND released_at IS NOT NULL AND started_at IS NOT NULL
            AND paused_at IS NOT NULL
            AND completed_at IS NULL AND cancelled_at IS NULL AND aborted_at IS NULL
            AND terminal_reason IS NULL)
        OR (status = 'COMPLETED' AND released_at IS NOT NULL AND started_at IS NOT NULL
            AND completed_at IS NOT NULL AND completed_at >= started_at
            AND cancelled_at IS NULL AND aborted_at IS NULL AND terminal_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL
            AND started_at IS NULL AND paused_at IS NULL
            AND completed_at IS NULL AND aborted_at IS NULL
            AND terminal_reason IS NOT NULL AND btrim(terminal_reason) <> '')
        OR (status = 'ABORTED' AND released_at IS NOT NULL AND started_at IS NOT NULL
            AND aborted_at IS NOT NULL AND aborted_at >= started_at
            AND completed_at IS NULL AND cancelled_at IS NULL
            AND terminal_reason IS NOT NULL AND btrim(terminal_reason) <> '')
    ),
    CONSTRAINT ck_mine_work_order_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_work_order_site_shift_status
    ON mine_work_order(site_id, shift_instance_id, status, priority DESC, id);
CREATE INDEX idx_mine_work_order_running_window
    ON mine_work_order(site_id, planned_start_at, planned_end_at, id)
    WHERE status IN ('RUNNING', 'PAUSED');

CREATE TABLE mine_work_order_assignment (
    id               UUID        NOT NULL,
    organization_id  UUID        NOT NULL,
    site_id           UUID        NOT NULL,
    work_order_id     UUID        NOT NULL,
    vehicle_id        UUID        NOT NULL,
    driver_person_id  UUID,
    valid_from        TIMESTAMPTZ NOT NULL,
    valid_to          TIMESTAMPTZ NOT NULL,
    active_period     TSTZRANGE   GENERATED ALWAYS AS (
        tstzrange(valid_from, valid_to, '[)')
    ) STORED,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    assignment_reason VARCHAR(500) NOT NULL DEFAULT 'INITIAL_DISPATCH',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        UUID,
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_work_order_assignment PRIMARY KEY (id),
    CONSTRAINT uk_mine_work_order_assignment_scope
        UNIQUE (id, organization_id, site_id),
    CONSTRAINT uk_mine_work_order_assignment_order_context
        UNIQUE (id, work_order_id, organization_id, site_id),
    CONSTRAINT uk_mine_work_order_assignment_record_context
        UNIQUE (id, work_order_id, vehicle_id, organization_id, site_id),
    CONSTRAINT fk_mine_work_order_assignment_order_scope
        FOREIGN KEY (work_order_id, organization_id, site_id)
        REFERENCES mine_work_order(id, organization_id, site_id),
    CONSTRAINT fk_mine_work_order_assignment_vehicle_scope
        FOREIGN KEY (vehicle_id, organization_id, site_id)
        REFERENCES mine_vehicle(id, organization_id, site_id),
    CONSTRAINT fk_mine_work_order_assignment_driver_organization
        FOREIGN KEY (driver_person_id, organization_id)
        REFERENCES mine_person(id, organization_id),
    CONSTRAINT fk_mine_work_order_assignment_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_work_order_assignment_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_work_order_assignment_period
        CHECK (valid_to > valid_from),
    CONSTRAINT ck_mine_work_order_assignment_status
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_mine_work_order_assignment_reason
        CHECK (btrim(assignment_reason) <> ''),
    CONSTRAINT ck_mine_work_order_assignment_audit
        CHECK (updated_at >= created_at AND version >= 0),
    CONSTRAINT ex_mine_work_order_assignment_vehicle
        EXCLUDE USING gist (site_id WITH =, vehicle_id WITH =, active_period WITH &&)
        WHERE (status = 'ACTIVE'),
    CONSTRAINT ex_mine_work_order_assignment_driver
        EXCLUDE USING gist (site_id WITH =, driver_person_id WITH =, active_period WITH &&)
        WHERE (status = 'ACTIVE' AND driver_person_id IS NOT NULL)
);

CREATE INDEX idx_mine_work_order_assignment_order_status
    ON mine_work_order_assignment(work_order_id, status, valid_from, id);
CREATE INDEX idx_mine_work_order_assignment_vehicle_time
    ON mine_work_order_assignment(site_id, vehicle_id, valid_from DESC, id);

CREATE TABLE mine_operation_spatial_event_queue (
    id                UUID         NOT NULL,
    event_id          UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    site_id           UUID         NOT NULL,
    vehicle_id        UUID,
    location_tag_id   UUID,
    zone_type         VARCHAR(16)  NOT NULL,
    zone_id           UUID,
    zone_version_id   UUID         NOT NULL,
    event_type        VARCHAR(16)  NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    ingest_sequence   BIGINT       NOT NULL,
    payload           JSONB        NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count     INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token       UUID,
    leased_until      TIMESTAMPTZ,
    last_error        VARCHAR(2000),
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at      TIMESTAMPTZ,
    CONSTRAINT pk_mine_operation_spatial_event_queue PRIMARY KEY (id),
    CONSTRAINT uk_mine_operation_spatial_event_queue_event UNIQUE (event_id),
    CONSTRAINT uk_mine_operation_queue_source_context
        UNIQUE (id, event_id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_queue_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_operation_queue_vehicle_scope
        FOREIGN KEY (vehicle_id, organization_id, site_id)
        REFERENCES mine_vehicle(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_queue_tag_scope
        FOREIGN KEY (location_tag_id, organization_id, site_id)
        REFERENCES mine_location_tag(id, organization_id, site_id),
    CONSTRAINT ck_mine_operation_queue_identity
        CHECK (vehicle_id IS NOT NULL OR location_tag_id IS NOT NULL OR payload ? 'subject'),
    CONSTRAINT ck_mine_operation_queue_zone_type
        CHECK (zone_type IN ('WAYPOINT', 'GEOFENCE')),
    CONSTRAINT ck_mine_operation_queue_event_type
        CHECK (event_type IN ('ENTER', 'EXIT', 'DWELL')),
    CONSTRAINT ck_mine_operation_queue_sequence CHECK (ingest_sequence > 0),
    CONSTRAINT ck_mine_operation_queue_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND octet_length(payload::TEXT) <= 65536
    ),
    CONSTRAINT ck_mine_operation_queue_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED', 'DEAD')),
    CONSTRAINT ck_mine_operation_queue_attempt
        CHECK (attempt_count BETWEEN 0 AND 100),
    CONSTRAINT ck_mine_operation_queue_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND leased_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND leased_until IS NULL)
    ),
    CONSTRAINT ck_mine_operation_queue_terminal CHECK (
        (status = 'PROCESSED' AND processed_at IS NOT NULL)
        OR (status <> 'PROCESSED' AND processed_at IS NULL)
    ),
    CONSTRAINT ck_mine_operation_queue_error
        CHECK (last_error IS NULL OR btrim(last_error) <> ''),
    CONSTRAINT ck_mine_operation_queue_failure_evidence CHECK (
        status NOT IN ('FAILED', 'DEAD')
        OR (attempt_count > 0 AND last_error IS NOT NULL)
    )
);

CREATE INDEX idx_mine_operation_queue_claim
    ON mine_operation_spatial_event_queue(
        next_attempt_at, occurred_at, ingest_sequence, id
    ) WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_mine_operation_queue_expired_lease
    ON mine_operation_spatial_event_queue(leased_until, id)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_mine_operation_queue_site_time
    ON mine_operation_spatial_event_queue(site_id, occurred_at DESC, event_id);

CREATE TABLE mine_operation_runtime_state (
    id                       UUID        NOT NULL,
    organization_id          UUID        NOT NULL,
    site_id                  UUID        NOT NULL,
    work_order_id            UUID        NOT NULL,
    assignment_id            UUID        NOT NULL,
    cycle_number             BIGINT      NOT NULL DEFAULT 1,
    cycle_status             VARCHAR(24) NOT NULL DEFAULT 'WAITING_LOAD',
    last_spatial_event_id    UUID,
    last_occurred_at         TIMESTAMPTZ,
    last_ingest_sequence     BIGINT      NOT NULL DEFAULT 0,
    evidence_snapshot        JSONB       NOT NULL DEFAULT '{}'::JSONB,
    started_at               TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_operation_runtime_state PRIMARY KEY (id),
    CONSTRAINT uk_mine_operation_runtime_assignment UNIQUE (assignment_id),
    CONSTRAINT uk_mine_operation_runtime_scope
        UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_runtime_order_scope
        FOREIGN KEY (work_order_id, organization_id, site_id)
        REFERENCES mine_work_order(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_runtime_assignment_scope
        FOREIGN KEY (assignment_id, work_order_id, organization_id, site_id)
        REFERENCES mine_work_order_assignment(
            id, work_order_id, organization_id, site_id
        ),
    CONSTRAINT ck_mine_operation_runtime_cycle CHECK (cycle_number > 0),
    CONSTRAINT ck_mine_operation_runtime_status CHECK (
        cycle_status IN ('WAITING_LOAD', 'LOADING', 'LOADED',
                         'HAULING', 'UNLOADING', 'COMPLETED')
    ),
    CONSTRAINT ck_mine_operation_runtime_frontier CHECK (
        (last_occurred_at IS NULL AND last_spatial_event_id IS NULL
            AND last_ingest_sequence = 0)
        OR (last_occurred_at IS NOT NULL AND last_spatial_event_id IS NOT NULL
            AND last_ingest_sequence > 0)
    ),
    CONSTRAINT ck_mine_operation_runtime_evidence CHECK (
        jsonb_typeof(evidence_snapshot) = 'object'
        AND octet_length(evidence_snapshot::TEXT) <= 32768
    ),
    CONSTRAINT ck_mine_operation_runtime_audit CHECK (version >= 0)
);

CREATE INDEX idx_mine_operation_runtime_order_status
    ON mine_operation_runtime_state(work_order_id, cycle_status, assignment_id);

CREATE TABLE mine_operation_candidate (
    id                       UUID         NOT NULL,
    organization_id          UUID         NOT NULL,
    site_id                  UUID         NOT NULL,
    spatial_queue_id         UUID         NOT NULL,
    spatial_event_id         UUID         NOT NULL,
    work_order_id            UUID,
    assignment_id            UUID,
    candidate_type           VARCHAR(32)  NOT NULL,
    reason_code              VARCHAR(64)  NOT NULL,
    occurred_at              TIMESTAMPTZ  NOT NULL,
    candidate_payload        JSONB        NOT NULL,
    status                   VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    resolution_action        VARCHAR(24),
    resolution_reason        VARCHAR(500),
    resolved_record_id       UUID,
    resolved_at              TIMESTAMPTZ,
    resolved_by              UUID,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_operation_candidate PRIMARY KEY (id),
    CONSTRAINT uk_mine_operation_candidate_event_reason
        UNIQUE (spatial_event_id, candidate_type, reason_code),
    CONSTRAINT uk_mine_operation_candidate_scope
        UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_candidate_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_operation_candidate_queue
        FOREIGN KEY (
            spatial_queue_id, spatial_event_id, organization_id, site_id
        ) REFERENCES mine_operation_spatial_event_queue(
            id, event_id, organization_id, site_id
        ),
    CONSTRAINT fk_mine_operation_candidate_order_scope
        FOREIGN KEY (work_order_id, organization_id, site_id)
        REFERENCES mine_work_order(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_candidate_assignment_scope
        FOREIGN KEY (assignment_id, work_order_id, organization_id, site_id)
        REFERENCES mine_work_order_assignment(
            id, work_order_id, organization_id, site_id
        ),
    CONSTRAINT fk_mine_operation_candidate_resolved_by
        FOREIGN KEY (resolved_by) REFERENCES sys_user(id),
    CONSTRAINT ck_mine_operation_candidate_type CHECK (
        candidate_type IN (
            'AMBIGUOUS_MATCH', 'OUT_OF_ORDER', 'UNEXPECTED_EVENT',
            'VEHICLE_MISMATCH', 'ZONE_VERSION_MISMATCH',
            'PAUSED_WORK_ORDER', 'CLOSED_SHIFT', 'PROCESSING_ERROR'
        )
    ),
    CONSTRAINT ck_mine_operation_candidate_reason
        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_mine_operation_candidate_assignment_context
        CHECK (assignment_id IS NULL OR work_order_id IS NOT NULL),
    CONSTRAINT ck_mine_operation_candidate_payload CHECK (
        jsonb_typeof(candidate_payload) = 'object'
        AND octet_length(candidate_payload::TEXT) <= 65536
    ),
    CONSTRAINT ck_mine_operation_candidate_status
        CHECK (status IN ('PENDING', 'RESOLVED', 'IGNORED', 'CONVERTED')),
    CONSTRAINT ck_mine_operation_candidate_resolution CHECK (
        (status = 'PENDING' AND resolution_action IS NULL
            AND resolution_reason IS NULL AND resolved_record_id IS NULL
            AND resolved_at IS NULL AND resolved_by IS NULL)
        OR (status = 'IGNORED' AND resolution_action = 'IGNORE'
            AND resolved_record_id IS NULL
            AND resolution_reason IS NOT NULL
            AND btrim(resolution_reason) <> ''
            AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
        OR (status = 'RESOLVED' AND resolution_action = 'ASSOCIATE'
            AND resolved_record_id IS NULL
            AND resolution_reason IS NOT NULL
            AND btrim(resolution_reason) <> ''
            AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
        OR (status = 'CONVERTED' AND resolution_action = 'CONVERT_MANUAL'
            AND resolved_record_id IS NOT NULL
            AND resolution_reason IS NOT NULL
            AND btrim(resolution_reason) <> ''
            AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
    ),
    CONSTRAINT ck_mine_operation_candidate_action CHECK (
        resolution_action IS NULL
        OR resolution_action IN ('ASSOCIATE', 'IGNORE', 'CONVERT_MANUAL')
    ),
    CONSTRAINT ck_mine_operation_candidate_audit
        CHECK (updated_at >= created_at AND version >= 0)
);

CREATE INDEX idx_mine_operation_candidate_site_status_time
    ON mine_operation_candidate(site_id, status, occurred_at DESC, id);
CREATE INDEX idx_mine_operation_candidate_work_order
    ON mine_operation_candidate(work_order_id, status, occurred_at DESC, id)
    WHERE work_order_id IS NOT NULL;

CREATE TABLE mine_operation_record (
    id                         UUID          NOT NULL,
    organization_id            UUID          NOT NULL,
    site_id                    UUID          NOT NULL,
    shift_instance_id          UUID          NOT NULL,
    work_order_id              UUID          NOT NULL,
    assignment_id              UUID          NOT NULL,
    cycle_number               BIGINT        NOT NULL,
    work_type                  VARCHAR(24)   NOT NULL,
    record_status              VARCHAR(16)   NOT NULL,
    vehicle_id                 UUID          NOT NULL,
    driver_person_id           UUID,
    route_version_id           UUID,
    load_geofence_version_id   UUID,
    unload_geofence_version_id UUID,
    started_at                 TIMESTAMPTZ   NOT NULL,
    load_completed_at          TIMESTAMPTZ,
    unloading_started_at       TIMESTAMPTZ,
    completed_at               TIMESTAMPTZ   NOT NULL,
    distance_meters            NUMERIC(16,3),
    actual_tons                NUMERIC(14,3),
    tons_source                VARCHAR(24),
    frozen_snapshot            JSONB         NOT NULL,
    record_source              VARCHAR(16)   NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                 UUID,
    CONSTRAINT pk_mine_operation_record PRIMARY KEY (id),
    CONSTRAINT uk_mine_operation_record_assignment_cycle
        UNIQUE (assignment_id, cycle_number),
    CONSTRAINT uk_mine_operation_record_scope
        UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_record_order_scope
        FOREIGN KEY (
            work_order_id, shift_instance_id, organization_id, site_id
        ) REFERENCES mine_work_order(
            id, shift_instance_id, organization_id, site_id
        ),
    CONSTRAINT fk_mine_operation_record_assignment_scope
        FOREIGN KEY (
            assignment_id, work_order_id, vehicle_id, organization_id, site_id
        ) REFERENCES mine_work_order_assignment(
            id, work_order_id, vehicle_id, organization_id, site_id
        ),
    CONSTRAINT fk_mine_operation_record_vehicle_scope
        FOREIGN KEY (vehicle_id, organization_id, site_id)
        REFERENCES mine_vehicle(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_record_driver_organization
        FOREIGN KEY (driver_person_id, organization_id)
        REFERENCES mine_person(id, organization_id),
    CONSTRAINT fk_mine_operation_record_route_scope
        FOREIGN KEY (route_version_id, organization_id, site_id)
        REFERENCES mine_route_version(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_record_load_geofence_scope
        FOREIGN KEY (load_geofence_version_id, organization_id, site_id)
        REFERENCES mine_geofence_version(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_record_unload_geofence_scope
        FOREIGN KEY (unload_geofence_version_id, organization_id, site_id)
        REFERENCES mine_geofence_version(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_record_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT ck_mine_operation_record_cycle CHECK (cycle_number > 0),
    CONSTRAINT ck_mine_operation_record_type CHECK (work_type IN ('HAULAGE')),
    CONSTRAINT ck_mine_operation_record_status
        CHECK (record_status IN ('COMPLETED', 'ABORTED')),
    CONSTRAINT ck_mine_operation_record_timeline CHECK (
        completed_at >= started_at
        AND (load_completed_at IS NULL OR load_completed_at >= started_at)
        AND (unloading_started_at IS NULL
            OR (load_completed_at IS NOT NULL
                AND unloading_started_at >= load_completed_at))
        AND (unloading_started_at IS NULL OR completed_at >= unloading_started_at)
    ),
    CONSTRAINT ck_mine_operation_record_measurements CHECK (
        (distance_meters IS NULL OR distance_meters >= 0)
        AND (actual_tons IS NULL OR actual_tons >= 0)
        AND ((actual_tons IS NULL AND tons_source IS NULL)
            OR (actual_tons IS NOT NULL
                AND tons_source IS NOT NULL
                AND tons_source IN ('SCALE', 'VEHICLE', 'MANUAL', 'ESTIMATED')))
    ),
    CONSTRAINT ck_mine_operation_record_snapshot CHECK (
        jsonb_typeof(frozen_snapshot) = 'object'
        AND octet_length(frozen_snapshot::TEXT) <= 65536
    ),
    CONSTRAINT ck_mine_operation_record_source
        CHECK (record_source IN ('AUTOMATIC', 'MANUAL')),
    CONSTRAINT ck_mine_operation_record_manual_actor
        CHECK (record_source <> 'MANUAL' OR created_by IS NOT NULL),
    CONSTRAINT ck_mine_operation_record_automatic_complete CHECK (
        record_source <> 'AUTOMATIC' OR record_status <> 'COMPLETED'
        OR (route_version_id IS NOT NULL
            AND load_geofence_version_id IS NOT NULL
            AND unload_geofence_version_id IS NOT NULL
            AND load_completed_at IS NOT NULL
            AND unloading_started_at IS NOT NULL)
    )
);

CREATE INDEX idx_mine_operation_record_site_completed
    ON mine_operation_record(site_id, completed_at DESC, id);
CREATE INDEX idx_mine_operation_record_shift_order
    ON mine_operation_record(shift_instance_id, work_order_id, completed_at DESC, id);
CREATE INDEX idx_mine_operation_record_vehicle_time
    ON mine_operation_record(vehicle_id, completed_at DESC, id);

CREATE TABLE mine_operation_evidence (
    id                  UUID        NOT NULL,
    organization_id     UUID        NOT NULL,
    site_id             UUID        NOT NULL,
    operation_record_id UUID        NOT NULL,
    evidence_role       VARCHAR(24) NOT NULL,
    spatial_event_id    UUID,
    occurred_at         TIMESTAMPTZ NOT NULL,
    ingest_sequence     BIGINT,
    evidence_payload    JSONB       NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    CONSTRAINT pk_mine_operation_evidence PRIMARY KEY (id),
    CONSTRAINT uk_mine_operation_evidence_role
        UNIQUE (operation_record_id, evidence_role),
    CONSTRAINT fk_mine_operation_evidence_record_scope
        FOREIGN KEY (operation_record_id, organization_id, site_id)
        REFERENCES mine_operation_record(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_evidence_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT ck_mine_operation_evidence_role CHECK (
        evidence_role IN ('LOAD_ENTER', 'LOAD_DWELL', 'LOAD_EXIT',
                          'UNLOAD_ENTER', 'UNLOAD_DWELL', 'MANUAL_SUPPORT')
    ),
    CONSTRAINT ck_mine_operation_evidence_sequence CHECK (
        (spatial_event_id IS NULL AND ingest_sequence IS NULL)
        OR (spatial_event_id IS NOT NULL AND ingest_sequence IS NOT NULL
            AND ingest_sequence > 0)
    ),
    CONSTRAINT ck_mine_operation_evidence_manual_actor
        CHECK (spatial_event_id IS NOT NULL OR created_by IS NOT NULL),
    CONSTRAINT ck_mine_operation_evidence_payload CHECK (
        jsonb_typeof(evidence_payload) = 'object'
        AND octet_length(evidence_payload::TEXT) <= 65536
    )
);

CREATE UNIQUE INDEX uk_mine_operation_evidence_spatial_event
    ON mine_operation_evidence(spatial_event_id)
    WHERE spatial_event_id IS NOT NULL;

CREATE TABLE mine_operation_correction (
    id                  UUID         NOT NULL,
    organization_id     UUID         NOT NULL,
    site_id             UUID         NOT NULL,
    operation_record_id UUID         NOT NULL,
    correction_version  INTEGER      NOT NULL,
    correction_type     VARCHAR(16)  NOT NULL,
    before_values       JSONB        NOT NULL,
    after_values        JSONB        NOT NULL,
    reason              VARCHAR(1000) NOT NULL,
    idempotency_key     UUID         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID         NOT NULL,
    CONSTRAINT pk_mine_operation_correction PRIMARY KEY (id),
    CONSTRAINT uk_mine_operation_correction_version
        UNIQUE (operation_record_id, correction_version),
    CONSTRAINT uk_mine_operation_correction_idempotency
        UNIQUE (site_id, idempotency_key),
    CONSTRAINT fk_mine_operation_correction_record_scope
        FOREIGN KEY (operation_record_id, organization_id, site_id)
        REFERENCES mine_operation_record(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_correction_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT ck_mine_operation_correction_version CHECK (correction_version > 0),
    CONSTRAINT ck_mine_operation_correction_type
        CHECK (correction_type IN ('AMEND', 'VOID', 'RESTORE')),
    CONSTRAINT ck_mine_operation_correction_values CHECK (
        jsonb_typeof(before_values) = 'object'
        AND jsonb_typeof(after_values) = 'object'
        AND before_values ?& ARRAY[
            'effective', 'distanceMeters', 'actualTons', 'tonsSource'
        ]
        AND after_values ?& ARRAY[
            'effective', 'distanceMeters', 'actualTons', 'tonsSource'
        ]
        AND octet_length(before_values::TEXT) <= 65536
        AND octet_length(after_values::TEXT) <= 65536
    ),
    CONSTRAINT ck_mine_operation_correction_reason CHECK (btrim(reason) <> '')
);

CREATE INDEX idx_mine_operation_correction_record_version
    ON mine_operation_correction(operation_record_id, correction_version DESC);

CREATE TABLE mine_operation_effective_record (
    operation_record_id        UUID          NOT NULL,
    organization_id            UUID          NOT NULL,
    site_id                    UUID          NOT NULL,
    shift_instance_id          UUID          NOT NULL,
    work_order_id              UUID          NOT NULL,
    assignment_id              UUID          NOT NULL,
    cycle_number               BIGINT        NOT NULL,
    work_type                  VARCHAR(24)   NOT NULL,
    record_status              VARCHAR(16)   NOT NULL,
    effective                  BOOLEAN       NOT NULL DEFAULT TRUE,
    vehicle_id                 UUID          NOT NULL,
    driver_person_id           UUID,
    started_at                 TIMESTAMPTZ   NOT NULL,
    completed_at               TIMESTAMPTZ   NOT NULL,
    distance_meters            NUMERIC(16,3),
    actual_tons                NUMERIC(14,3),
    tons_source                VARCHAR(24),
    correction_version         INTEGER       NOT NULL DEFAULT 0,
    projected_at               TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_operation_effective_record PRIMARY KEY (operation_record_id),
    CONSTRAINT fk_mine_operation_effective_source_scope
        FOREIGN KEY (operation_record_id, organization_id, site_id)
        REFERENCES mine_operation_record(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_effective_order_scope
        FOREIGN KEY (
            work_order_id, shift_instance_id, organization_id, site_id
        ) REFERENCES mine_work_order(
            id, shift_instance_id, organization_id, site_id
        ),
    CONSTRAINT fk_mine_operation_effective_assignment_scope
        FOREIGN KEY (
            assignment_id, work_order_id, vehicle_id, organization_id, site_id
        ) REFERENCES mine_work_order_assignment(
            id, work_order_id, vehicle_id, organization_id, site_id
        ),
    CONSTRAINT fk_mine_operation_effective_vehicle_scope
        FOREIGN KEY (vehicle_id, organization_id, site_id)
        REFERENCES mine_vehicle(id, organization_id, site_id),
    CONSTRAINT fk_mine_operation_effective_driver_organization
        FOREIGN KEY (driver_person_id, organization_id)
        REFERENCES mine_person(id, organization_id),
    CONSTRAINT ck_mine_operation_effective_cycle CHECK (cycle_number > 0),
    CONSTRAINT ck_mine_operation_effective_type CHECK (work_type IN ('HAULAGE')),
    CONSTRAINT ck_mine_operation_effective_status
        CHECK (record_status IN ('COMPLETED', 'ABORTED')),
    CONSTRAINT ck_mine_operation_effective_timeline CHECK (completed_at >= started_at),
    CONSTRAINT ck_mine_operation_effective_measurements CHECK (
        (distance_meters IS NULL OR distance_meters >= 0)
        AND (actual_tons IS NULL OR actual_tons >= 0)
        AND ((actual_tons IS NULL AND tons_source IS NULL)
            OR (actual_tons IS NOT NULL
                AND tons_source IS NOT NULL
                AND tons_source IN ('SCALE', 'VEHICLE', 'MANUAL', 'ESTIMATED')))
    ),
    CONSTRAINT ck_mine_operation_effective_correction CHECK (correction_version >= 0)
);

CREATE INDEX idx_mine_operation_effective_shift_summary
    ON mine_operation_effective_record(
        site_id, shift_instance_id, effective, record_status, completed_at, operation_record_id
    );
CREATE INDEX idx_mine_operation_effective_order_summary
    ON mine_operation_effective_record(
        work_order_id, effective, record_status, completed_at, operation_record_id
    );

CREATE TABLE mine_operation_outbox (
    id                UUID         NOT NULL,
    organization_id   UUID         NOT NULL,
    site_id           UUID         NOT NULL,
    aggregate_type    VARCHAR(32)  NOT NULL,
    aggregate_id      UUID         NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    event_version     INTEGER      NOT NULL,
    payload           JSONB        NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count     INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token       UUID,
    leased_until      TIMESTAMPTZ,
    last_error        VARCHAR(2000),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at      TIMESTAMPTZ,
    CONSTRAINT pk_mine_operation_outbox PRIMARY KEY (id),
    CONSTRAINT fk_mine_operation_outbox_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT ck_mine_operation_outbox_aggregate CHECK (
        aggregate_type IN ('SHIFT_INSTANCE', 'WORK_ORDER',
                           'OPERATION_RECORD', 'OPERATION_CANDIDATE')
    ),
    CONSTRAINT ck_mine_operation_outbox_event
        CHECK (event_type = 'realtime.operation.updated' AND event_version = 1),
    CONSTRAINT ck_mine_operation_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND octet_length(payload::TEXT) <= 65536
    ),
    CONSTRAINT ck_mine_operation_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'DEAD')),
    CONSTRAINT ck_mine_operation_outbox_attempt
        CHECK (attempt_count BETWEEN 0 AND 100),
    CONSTRAINT ck_mine_operation_outbox_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND leased_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND leased_until IS NULL)
    ),
    CONSTRAINT ck_mine_operation_outbox_publication CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    ),
    CONSTRAINT ck_mine_operation_outbox_error
        CHECK (last_error IS NULL OR btrim(last_error) <> ''),
    CONSTRAINT ck_mine_operation_outbox_failure_evidence CHECK (
        status NOT IN ('FAILED', 'DEAD')
        OR (attempt_count > 0 AND last_error IS NOT NULL)
    )
);

CREATE INDEX idx_mine_operation_outbox_claim
    ON mine_operation_outbox(next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_mine_operation_outbox_expired_lease
    ON mine_operation_outbox(leased_until, id)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_mine_operation_outbox_aggregate
    ON mine_operation_outbox(aggregate_type, aggregate_id, created_at DESC, id);

ALTER TABLE mine_operation_candidate
    ADD CONSTRAINT fk_mine_operation_candidate_resolved_record_scope
        FOREIGN KEY (resolved_record_id, organization_id, site_id)
        REFERENCES mine_operation_record(id, organization_id, site_id);

CREATE FUNCTION mine_validate_shift_instance_definition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    definition_row mine_shift_definition%ROWTYPE;
BEGIN
    SELECT * INTO definition_row
    FROM mine_shift_definition
    WHERE id = NEW.shift_definition_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    IF definition_row.status <> 'ACTIVE'
       OR NEW.business_date < definition_row.valid_from
       OR (definition_row.valid_to IS NOT NULL
           AND NEW.business_date > definition_row.valid_to) THEN
        RAISE EXCEPTION '班次实例营业日期不在活动班次模板有效期内'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_work_order_window_and_release()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    shift_row mine_shift_instance%ROWTYPE;
    route_published BOOLEAN;
    load_published BOOLEAN;
    unload_published BOOLEAN;
BEGIN
    SELECT * INTO shift_row
    FROM mine_shift_instance
    WHERE id = NEW.shift_instance_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    IF NEW.planned_start_at < shift_row.planned_start_at
       OR NEW.planned_end_at > shift_row.planned_end_at THEN
        RAISE EXCEPTION '作业单计划窗口必须完整落在班次计划窗口内'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.released_at IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM mine_route_version
            WHERE id = NEW.route_version_id
              AND organization_id = NEW.organization_id
              AND site_id = NEW.site_id
              AND status = 'PUBLISHED'
        ) INTO route_published;
        SELECT EXISTS (
            SELECT 1 FROM mine_geofence_version
            WHERE id = NEW.load_geofence_version_id
              AND organization_id = NEW.organization_id
              AND site_id = NEW.site_id
              AND status = 'PUBLISHED'
              AND geofence_type = 'LOAD'
        ) INTO load_published;
        SELECT EXISTS (
            SELECT 1 FROM mine_geofence_version
            WHERE id = NEW.unload_geofence_version_id
              AND organization_id = NEW.organization_id
              AND site_id = NEW.site_id
              AND status = 'PUBLISHED'
              AND geofence_type = 'UNLOAD'
        ) INTO unload_published;
        IF NOT route_published OR NOT load_published OR NOT unload_published THEN
            RAISE EXCEPTION '发布作业单必须冻结本矿区已发布路线及装卸区域版本'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_work_order_assignment_window()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    order_row mine_work_order%ROWTYPE;
BEGIN
    SELECT * INTO order_row
    FROM mine_work_order
    WHERE id = NEW.work_order_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    IF NEW.valid_from < order_row.planned_start_at
       OR NEW.valid_to > order_row.planned_end_at THEN
        RAISE EXCEPTION '作业分配有效期必须完整落在作业单计划窗口内'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_operation_record_context()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    assignment_driver UUID;
    order_row mine_work_order%ROWTYPE;
BEGIN
    SELECT driver_person_id INTO assignment_driver
    FROM mine_work_order_assignment
    WHERE id = NEW.assignment_id
      AND work_order_id = NEW.work_order_id
      AND vehicle_id = NEW.vehicle_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id;
    IF NOT FOUND OR assignment_driver IS DISTINCT FROM NEW.driver_person_id THEN
        RAISE EXCEPTION '执行事实车辆和司机必须与发生时作业分配一致'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO order_row
    FROM mine_work_order
    WHERE id = NEW.work_order_id
      AND shift_instance_id = NEW.shift_instance_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id;
    IF NOT FOUND OR order_row.work_type <> NEW.work_type THEN
        RAISE EXCEPTION '执行事实班次和作业类型必须与作业单一致'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.record_source = 'AUTOMATIC' AND (
        order_row.route_version_id IS DISTINCT FROM NEW.route_version_id
        OR order_row.load_geofence_version_id
            IS DISTINCT FROM NEW.load_geofence_version_id
        OR order_row.unload_geofence_version_id
            IS DISTINCT FROM NEW.unload_geofence_version_id
    ) THEN
        RAISE EXCEPTION '自动执行事实必须使用作业单冻结的空间版本'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_operation_effective_source()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_row mine_operation_record%ROWTYPE;
    correction_row mine_operation_correction%ROWTYPE;
BEGIN
    SELECT * INTO source_row
    FROM mine_operation_record
    WHERE id = NEW.operation_record_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id;
    IF NOT FOUND
       OR source_row.shift_instance_id <> NEW.shift_instance_id
       OR source_row.work_order_id <> NEW.work_order_id
       OR source_row.assignment_id <> NEW.assignment_id
       OR source_row.cycle_number <> NEW.cycle_number
       OR source_row.work_type <> NEW.work_type
       OR source_row.record_status <> NEW.record_status
       OR source_row.vehicle_id <> NEW.vehicle_id
       OR source_row.driver_person_id IS DISTINCT FROM NEW.driver_person_id
       OR source_row.started_at <> NEW.started_at
       OR source_row.completed_at <> NEW.completed_at THEN
        RAISE EXCEPTION '有效投影身份和时间上下文必须与来源执行事实一致'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.correction_version = 0 AND (
        source_row.distance_meters IS DISTINCT FROM NEW.distance_meters
        OR source_row.actual_tons IS DISTINCT FROM NEW.actual_tons
        OR source_row.tons_source IS DISTINCT FROM NEW.tons_source
    ) THEN
        RAISE EXCEPTION '未修正投影的度量值必须与来源执行事实一致'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.correction_version > 0 THEN
        SELECT * INTO correction_row
        FROM mine_operation_correction
        WHERE operation_record_id = NEW.operation_record_id
          AND correction_version = NEW.correction_version;
        IF NOT FOUND THEN
            RAISE EXCEPTION '有效投影引用的修正版本不存在'
                USING ERRCODE = '23514';
        END IF;
        IF NOT correction_row.after_values @> jsonb_build_object(
            'effective', NEW.effective,
            'distanceMeters', NEW.distance_meters,
            'actualTons', NEW.actual_tons,
            'tonsSource', NEW.tons_source
        ) THEN
            RAISE EXCEPTION '有效投影必须与修正后的完整快照一致'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_reject_closed_shift_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION '已关闭或已取消班次不可修改或删除'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_reject_released_work_order_snapshot_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.released_at IS NOT NULL AND TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '已发布作业单的计划和版本快照不可修改或删除'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.released_at IS NOT NULL AND (
        NEW.order_number IS DISTINCT FROM OLD.order_number
        OR NEW.title IS DISTINCT FROM OLD.title
        OR NEW.work_type IS DISTINCT FROM OLD.work_type
        OR NEW.shift_instance_id IS DISTINCT FROM OLD.shift_instance_id
        OR NEW.planned_start_at IS DISTINCT FROM OLD.planned_start_at
        OR NEW.planned_end_at IS DISTINCT FROM OLD.planned_end_at
        OR NEW.planned_cycles IS DISTINCT FROM OLD.planned_cycles
        OR NEW.planned_tons IS DISTINCT FROM OLD.planned_tons
        OR NEW.priority IS DISTINCT FROM OLD.priority
        OR NEW.route_version_id IS DISTINCT FROM OLD.route_version_id
        OR NEW.load_geofence_version_id IS DISTINCT FROM OLD.load_geofence_version_id
        OR NEW.unload_geofence_version_id IS DISTINCT FROM OLD.unload_geofence_version_id
        OR NEW.release_snapshot IS DISTINCT FROM OLD.release_snapshot
    ) THEN
        RAISE EXCEPTION '已发布作业单的计划和版本快照不可修改或删除'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_reject_operation_fact_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '运营执行记录、证据和修正事实只允许追加写'
        USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER trg_mine_shift_instance_validate_definition
BEFORE INSERT OR UPDATE OF shift_definition_id, organization_id, site_id, business_date
ON mine_shift_instance
FOR EACH ROW EXECUTE FUNCTION mine_validate_shift_instance_definition();

CREATE TRIGGER trg_mine_shift_instance_closed_immutable
BEFORE UPDATE OR DELETE ON mine_shift_instance
FOR EACH ROW EXECUTE FUNCTION mine_reject_closed_shift_change();

CREATE TRIGGER trg_mine_work_order_validate_window
BEFORE INSERT OR UPDATE OF
    shift_instance_id, organization_id, site_id,
    planned_start_at, planned_end_at, released_at,
    route_version_id, load_geofence_version_id, unload_geofence_version_id
ON mine_work_order
FOR EACH ROW EXECUTE FUNCTION mine_validate_work_order_window_and_release();

CREATE TRIGGER trg_mine_work_order_snapshot_immutable
BEFORE UPDATE OR DELETE ON mine_work_order
FOR EACH ROW EXECUTE FUNCTION mine_reject_released_work_order_snapshot_change();

CREATE TRIGGER trg_mine_work_order_assignment_validate_window
BEFORE INSERT OR UPDATE OF
    work_order_id, organization_id, site_id, valid_from, valid_to
ON mine_work_order_assignment
FOR EACH ROW EXECUTE FUNCTION mine_validate_work_order_assignment_window();

CREATE TRIGGER trg_mine_operation_record_validate_context
BEFORE INSERT ON mine_operation_record
FOR EACH ROW EXECUTE FUNCTION mine_validate_operation_record_context();

CREATE TRIGGER trg_mine_operation_effective_validate_source
BEFORE INSERT OR UPDATE ON mine_operation_effective_record
FOR EACH ROW EXECUTE FUNCTION mine_validate_operation_effective_source();

CREATE TRIGGER trg_mine_operation_record_immutable
BEFORE UPDATE OR DELETE ON mine_operation_record
FOR EACH ROW EXECUTE FUNCTION mine_reject_operation_fact_change();

CREATE TRIGGER trg_mine_operation_evidence_immutable
BEFORE UPDATE OR DELETE ON mine_operation_evidence
FOR EACH ROW EXECUTE FUNCTION mine_reject_operation_fact_change();

CREATE TRIGGER trg_mine_operation_correction_immutable
BEFORE UPDATE OR DELETE ON mine_operation_correction
FOR EACH ROW EXECUTE FUNCTION mine_reject_operation_fact_change();

COMMENT ON TABLE mine_shift_definition IS '矿区班次模板及本地时间有效期配置';
COMMENT ON COLUMN mine_shift_definition.id IS '班次模板唯一标识';
COMMENT ON COLUMN mine_shift_definition.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_shift_definition.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_shift_definition.definition_code IS '矿区内稳定班次模板编码';
COMMENT ON COLUMN mine_shift_definition.name IS '班次模板名称';
COMMENT ON COLUMN mine_shift_definition.start_local IS '矿区本地计划开始时间';
COMMENT ON COLUMN mine_shift_definition.end_local IS '矿区本地计划结束时间';
COMMENT ON COLUMN mine_shift_definition.crosses_midnight IS '班次是否跨越本地午夜';
COMMENT ON COLUMN mine_shift_definition.valid_from IS '模板生效营业日期';
COMMENT ON COLUMN mine_shift_definition.valid_to IS '模板失效营业日期，包含该日期';
COMMENT ON COLUMN mine_shift_definition.status IS '模板状态：活动或已归档';
COMMENT ON COLUMN mine_shift_definition.created_at IS '模板创建时间';
COMMENT ON COLUMN mine_shift_definition.created_by IS '模板创建用户标识';
COMMENT ON COLUMN mine_shift_definition.updated_at IS '模板最近更新时间';
COMMENT ON COLUMN mine_shift_definition.updated_by IS '模板最近更新用户标识';
COMMENT ON COLUMN mine_shift_definition.archived_at IS '模板归档时间';
COMMENT ON COLUMN mine_shift_definition.version IS '模板乐观锁版本号';

COMMENT ON TABLE mine_shift_instance IS '按营业日期实例化的矿区实际班次';
COMMENT ON COLUMN mine_shift_instance.id IS '班次实例唯一标识';
COMMENT ON COLUMN mine_shift_instance.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_shift_instance.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_shift_instance.shift_definition_id IS '来源班次模板唯一标识';
COMMENT ON COLUMN mine_shift_instance.business_date IS '按矿区时区归集的营业日期';
COMMENT ON COLUMN mine_shift_instance.planned_start_at IS '计划开始时间';
COMMENT ON COLUMN mine_shift_instance.planned_end_at IS '计划结束时间';
COMMENT ON COLUMN mine_shift_instance.actual_start_at IS '实际开始时间';
COMMENT ON COLUMN mine_shift_instance.actual_end_at IS '实际结束时间';
COMMENT ON COLUMN mine_shift_instance.supervisor_person_id IS '班次负责人组织级人员标识';
COMMENT ON COLUMN mine_shift_instance.definition_snapshot IS '实例化时冻结的班次模板快照';
COMMENT ON COLUMN mine_shift_instance.status IS '班次状态：计划、开放、关闭或取消';
COMMENT ON COLUMN mine_shift_instance.opened_at IS '班次开启操作时间';
COMMENT ON COLUMN mine_shift_instance.opened_by IS '班次开启用户标识';
COMMENT ON COLUMN mine_shift_instance.closed_at IS '班次关闭操作时间';
COMMENT ON COLUMN mine_shift_instance.closed_by IS '班次关闭用户标识';
COMMENT ON COLUMN mine_shift_instance.cancelled_at IS '班次取消操作时间';
COMMENT ON COLUMN mine_shift_instance.cancelled_by IS '班次取消用户标识';
COMMENT ON COLUMN mine_shift_instance.cancellation_reason IS '班次取消原因';
COMMENT ON COLUMN mine_shift_instance.created_at IS '班次实例创建时间';
COMMENT ON COLUMN mine_shift_instance.created_by IS '班次实例创建用户标识';
COMMENT ON COLUMN mine_shift_instance.updated_at IS '班次实例最近更新时间';
COMMENT ON COLUMN mine_shift_instance.updated_by IS '班次实例最近更新用户标识';
COMMENT ON COLUMN mine_shift_instance.version IS '班次实例乐观锁版本号';

COMMENT ON TABLE mine_work_order IS '班次内可发布和调度的通用作业单';
COMMENT ON COLUMN mine_work_order.id IS '作业单唯一标识';
COMMENT ON COLUMN mine_work_order.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_work_order.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_work_order.shift_instance_id IS '所属班次实例唯一标识';
COMMENT ON COLUMN mine_work_order.order_number IS '矿区内唯一作业单号';
COMMENT ON COLUMN mine_work_order.title IS '作业单标题';
COMMENT ON COLUMN mine_work_order.work_type IS '作业类型，首期为运输作业';
COMMENT ON COLUMN mine_work_order.planned_start_at IS '计划作业开始时间';
COMMENT ON COLUMN mine_work_order.planned_end_at IS '计划作业结束时间';
COMMENT ON COLUMN mine_work_order.planned_cycles IS '计划完成趟数';
COMMENT ON COLUMN mine_work_order.planned_tons IS '计划运输吨位';
COMMENT ON COLUMN mine_work_order.priority IS '一至一百的调度优先级';
COMMENT ON COLUMN mine_work_order.route_version_id IS '发布时冻结的路线版本标识';
COMMENT ON COLUMN mine_work_order.load_geofence_version_id IS '发布时冻结的装载围栏版本标识';
COMMENT ON COLUMN mine_work_order.unload_geofence_version_id IS '发布时冻结的卸载围栏版本标识';
COMMENT ON COLUMN mine_work_order.release_snapshot IS '发布时冻结的业务名称和配置快照';
COMMENT ON COLUMN mine_work_order.status IS '作业单生命周期状态';
COMMENT ON COLUMN mine_work_order.released_at IS '作业单发布时间';
COMMENT ON COLUMN mine_work_order.released_by IS '作业单发布用户标识';
COMMENT ON COLUMN mine_work_order.started_at IS '作业单启动时间';
COMMENT ON COLUMN mine_work_order.started_by IS '作业单启动用户标识';
COMMENT ON COLUMN mine_work_order.paused_at IS '作业单最近暂停时间';
COMMENT ON COLUMN mine_work_order.paused_by IS '作业单最近暂停用户标识';
COMMENT ON COLUMN mine_work_order.completed_at IS '作业单完成时间';
COMMENT ON COLUMN mine_work_order.completed_by IS '作业单完成人员用户标识';
COMMENT ON COLUMN mine_work_order.cancelled_at IS '作业单取消时间';
COMMENT ON COLUMN mine_work_order.cancelled_by IS '作业单取消用户标识';
COMMENT ON COLUMN mine_work_order.aborted_at IS '作业单终止时间';
COMMENT ON COLUMN mine_work_order.aborted_by IS '作业单终止用户标识';
COMMENT ON COLUMN mine_work_order.terminal_reason IS '取消或异常终止原因';
COMMENT ON COLUMN mine_work_order.created_at IS '作业单创建时间';
COMMENT ON COLUMN mine_work_order.created_by IS '作业单创建用户标识';
COMMENT ON COLUMN mine_work_order.updated_at IS '作业单最近更新时间';
COMMENT ON COLUMN mine_work_order.updated_by IS '作业单最近更新用户标识';
COMMENT ON COLUMN mine_work_order.version IS '作业单乐观锁版本号';

COMMENT ON TABLE mine_work_order_assignment IS '作业单车辆和司机的带时间区间调度分配';
COMMENT ON COLUMN mine_work_order_assignment.id IS '作业分配唯一标识';
COMMENT ON COLUMN mine_work_order_assignment.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_work_order_assignment.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_work_order_assignment.work_order_id IS '所属作业单唯一标识';
COMMENT ON COLUMN mine_work_order_assignment.vehicle_id IS '分配车辆唯一标识';
COMMENT ON COLUMN mine_work_order_assignment.driver_person_id IS '分配司机组织级人员标识';
COMMENT ON COLUMN mine_work_order_assignment.valid_from IS '分配生效时间，包含该时刻';
COMMENT ON COLUMN mine_work_order_assignment.valid_to IS '分配结束时间，不包含该时刻';
COMMENT ON COLUMN mine_work_order_assignment.active_period IS '由生效和结束时间生成的左闭右开区间';
COMMENT ON COLUMN mine_work_order_assignment.status IS '分配状态：活动、完成或取消';
COMMENT ON COLUMN mine_work_order_assignment.assignment_reason IS '新增或调整调度分配的原因';
COMMENT ON COLUMN mine_work_order_assignment.created_at IS '作业分配创建时间';
COMMENT ON COLUMN mine_work_order_assignment.created_by IS '作业分配创建用户标识';
COMMENT ON COLUMN mine_work_order_assignment.updated_at IS '作业分配最近更新时间';
COMMENT ON COLUMN mine_work_order_assignment.updated_by IS '作业分配最近更新用户标识';
COMMENT ON COLUMN mine_work_order_assignment.version IS '作业分配乐观锁版本号';

COMMENT ON TABLE mine_operation_spatial_event_queue IS '运营消费者持久保存和有界排序的空间事件队列';
COMMENT ON COLUMN mine_operation_spatial_event_queue.id IS '队列记录唯一标识';
COMMENT ON COLUMN mine_operation_spatial_event_queue.event_id IS '上游不可变空间事件唯一标识';
COMMENT ON COLUMN mine_operation_spatial_event_queue.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_operation_spatial_event_queue.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_operation_spatial_event_queue.vehicle_id IS '发生时解析出的车辆标识';
COMMENT ON COLUMN mine_operation_spatial_event_queue.location_tag_id IS '发生时定位标签标识';
COMMENT ON COLUMN mine_operation_spatial_event_queue.zone_type IS '空间区域类型：途径点或围栏';
COMMENT ON COLUMN mine_operation_spatial_event_queue.zone_id IS '稳定空间区域对象标识';
COMMENT ON COLUMN mine_operation_spatial_event_queue.zone_version_id IS '不可变空间区域版本标识';
COMMENT ON COLUMN mine_operation_spatial_event_queue.event_type IS '空间事件类型：进入、离开或停留';
COMMENT ON COLUMN mine_operation_spatial_event_queue.occurred_at IS '空间事件业务发生时间';
COMMENT ON COLUMN mine_operation_spatial_event_queue.ingest_sequence IS '锚点位置事实摄入序号';
COMMENT ON COLUMN mine_operation_spatial_event_queue.payload IS '规范化空间事件完整载荷';
COMMENT ON COLUMN mine_operation_spatial_event_queue.status IS '队列处理状态';
COMMENT ON COLUMN mine_operation_spatial_event_queue.attempt_count IS '处理尝试次数';
COMMENT ON COLUMN mine_operation_spatial_event_queue.next_attempt_at IS '允许下次领取的时间';
COMMENT ON COLUMN mine_operation_spatial_event_queue.lease_token IS '本次处理随机租约令牌';
COMMENT ON COLUMN mine_operation_spatial_event_queue.leased_until IS '本次处理租约到期时间';
COMMENT ON COLUMN mine_operation_spatial_event_queue.last_error IS '最近一次固定长度错误摘要';
COMMENT ON COLUMN mine_operation_spatial_event_queue.received_at IS '业务消费者接收时间';
COMMENT ON COLUMN mine_operation_spatial_event_queue.processed_at IS '业务处理成功时间';

COMMENT ON TABLE mine_operation_runtime_state IS '按作业分配保存的当前运输循环运行状态';
COMMENT ON COLUMN mine_operation_runtime_state.id IS '运行状态唯一标识';
COMMENT ON COLUMN mine_operation_runtime_state.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_operation_runtime_state.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_operation_runtime_state.work_order_id IS '所属作业单唯一标识';
COMMENT ON COLUMN mine_operation_runtime_state.assignment_id IS '所属作业分配唯一标识';
COMMENT ON COLUMN mine_operation_runtime_state.cycle_number IS '分配内从一开始递增的当前趟次';
COMMENT ON COLUMN mine_operation_runtime_state.cycle_status IS '当前运输循环阶段';
COMMENT ON COLUMN mine_operation_runtime_state.last_spatial_event_id IS '最近接受的空间事件标识';
COMMENT ON COLUMN mine_operation_runtime_state.last_occurred_at IS '最近接受证据的发生时间';
COMMENT ON COLUMN mine_operation_runtime_state.last_ingest_sequence IS '最近接受证据的摄入序号';
COMMENT ON COLUMN mine_operation_runtime_state.evidence_snapshot IS '当前循环固定角色证据快照';
COMMENT ON COLUMN mine_operation_runtime_state.started_at IS '当前循环开始时间';
COMMENT ON COLUMN mine_operation_runtime_state.updated_at IS '运行状态最近更新时间';
COMMENT ON COLUMN mine_operation_runtime_state.version IS '运行状态乐观锁版本号';

COMMENT ON TABLE mine_operation_candidate IS '不能安全自动归属或推进的运营候选事件';
COMMENT ON COLUMN mine_operation_candidate.id IS '运营候选唯一标识';
COMMENT ON COLUMN mine_operation_candidate.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_operation_candidate.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_operation_candidate.spatial_queue_id IS '来源空间事件队列记录标识';
COMMENT ON COLUMN mine_operation_candidate.spatial_event_id IS '来源空间事件唯一标识';
COMMENT ON COLUMN mine_operation_candidate.work_order_id IS '可能关联的作业单标识';
COMMENT ON COLUMN mine_operation_candidate.assignment_id IS '可能关联的作业分配标识';
COMMENT ON COLUMN mine_operation_candidate.candidate_type IS '低基数候选类型';
COMMENT ON COLUMN mine_operation_candidate.reason_code IS '固定候选原因编码';
COMMENT ON COLUMN mine_operation_candidate.occurred_at IS '候选业务发生时间';
COMMENT ON COLUMN mine_operation_candidate.candidate_payload IS '候选匹配上下文和原始证据快照';
COMMENT ON COLUMN mine_operation_candidate.status IS '候选处置状态';
COMMENT ON COLUMN mine_operation_candidate.resolution_action IS '人工处置动作';
COMMENT ON COLUMN mine_operation_candidate.resolution_reason IS '人工处置原因';
COMMENT ON COLUMN mine_operation_candidate.resolved_record_id IS '处置后形成的执行记录标识';
COMMENT ON COLUMN mine_operation_candidate.resolved_at IS '候选处置时间';
COMMENT ON COLUMN mine_operation_candidate.resolved_by IS '候选处置用户标识';
COMMENT ON COLUMN mine_operation_candidate.created_at IS '候选创建时间';
COMMENT ON COLUMN mine_operation_candidate.updated_at IS '候选最近更新时间';
COMMENT ON COLUMN mine_operation_candidate.version IS '候选乐观锁版本号';

COMMENT ON TABLE mine_operation_record IS '一次完成或异常终止运输循环的不可变执行事实';
COMMENT ON COLUMN mine_operation_record.id IS '执行记录唯一标识';
COMMENT ON COLUMN mine_operation_record.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_operation_record.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_operation_record.shift_instance_id IS '发生时班次实例唯一标识';
COMMENT ON COLUMN mine_operation_record.work_order_id IS '发生时作业单唯一标识';
COMMENT ON COLUMN mine_operation_record.assignment_id IS '发生时作业分配唯一标识';
COMMENT ON COLUMN mine_operation_record.cycle_number IS '作业分配内趟次序号';
COMMENT ON COLUMN mine_operation_record.work_type IS '执行记录作业类型';
COMMENT ON COLUMN mine_operation_record.record_status IS '执行结果状态：完成或异常终止';
COMMENT ON COLUMN mine_operation_record.vehicle_id IS '发生时车辆唯一标识';
COMMENT ON COLUMN mine_operation_record.driver_person_id IS '发生时司机组织级人员标识';
COMMENT ON COLUMN mine_operation_record.route_version_id IS '发生时冻结的路线版本标识';
COMMENT ON COLUMN mine_operation_record.load_geofence_version_id IS '发生时冻结的装载围栏版本标识';
COMMENT ON COLUMN mine_operation_record.unload_geofence_version_id IS '发生时冻结的卸载围栏版本标识';
COMMENT ON COLUMN mine_operation_record.started_at IS '本趟运输开始时间';
COMMENT ON COLUMN mine_operation_record.load_completed_at IS '本趟装载完成时间';
COMMENT ON COLUMN mine_operation_record.unloading_started_at IS '本趟开始卸载时间';
COMMENT ON COLUMN mine_operation_record.completed_at IS '本趟事实结束时间';
COMMENT ON COLUMN mine_operation_record.distance_meters IS '本趟运输距离，单位米';
COMMENT ON COLUMN mine_operation_record.actual_tons IS '本趟实际吨位，未知时为空';
COMMENT ON COLUMN mine_operation_record.tons_source IS '实际吨位数据来源';
COMMENT ON COLUMN mine_operation_record.frozen_snapshot IS '发生时身份、版本和业务名称冻结快照';
COMMENT ON COLUMN mine_operation_record.record_source IS '执行事实来源：自动识别或人工补录';
COMMENT ON COLUMN mine_operation_record.created_at IS '执行事实创建时间';
COMMENT ON COLUMN mine_operation_record.created_by IS '人工事实创建用户标识';

COMMENT ON TABLE mine_operation_evidence IS '执行记录固定角色的不可变空间或人工证据';
COMMENT ON COLUMN mine_operation_evidence.id IS '执行证据唯一标识';
COMMENT ON COLUMN mine_operation_evidence.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_operation_evidence.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_operation_evidence.operation_record_id IS '所属执行记录唯一标识';
COMMENT ON COLUMN mine_operation_evidence.evidence_role IS '证据在运输循环中的固定角色';
COMMENT ON COLUMN mine_operation_evidence.spatial_event_id IS '来源空间事件标识，人工证据可为空';
COMMENT ON COLUMN mine_operation_evidence.occurred_at IS '证据业务发生时间';
COMMENT ON COLUMN mine_operation_evidence.ingest_sequence IS '来源空间事实摄入序号';
COMMENT ON COLUMN mine_operation_evidence.evidence_payload IS '不可变证据载荷快照';
COMMENT ON COLUMN mine_operation_evidence.created_at IS '执行证据创建时间';
COMMENT ON COLUMN mine_operation_evidence.created_by IS '人工证据创建用户标识';

COMMENT ON TABLE mine_operation_correction IS '对执行事实进行补录、修正或作废的追加式记录';
COMMENT ON COLUMN mine_operation_correction.id IS '事实修正唯一标识';
COMMENT ON COLUMN mine_operation_correction.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_operation_correction.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_operation_correction.operation_record_id IS '被修正执行记录唯一标识';
COMMENT ON COLUMN mine_operation_correction.correction_version IS '执行记录内单调递增修正版本';
COMMENT ON COLUMN mine_operation_correction.correction_type IS '修正类型：更正、作废或恢复';
COMMENT ON COLUMN mine_operation_correction.before_values IS '修正前规范化有效值快照';
COMMENT ON COLUMN mine_operation_correction.after_values IS '修正后规范化有效值快照';
COMMENT ON COLUMN mine_operation_correction.reason IS '人工修正业务原因';
COMMENT ON COLUMN mine_operation_correction.idempotency_key IS '矿区范围内命令幂等键';
COMMENT ON COLUMN mine_operation_correction.created_at IS '事实修正创建时间';
COMMENT ON COLUMN mine_operation_correction.created_by IS '事实修正操作用户标识';

COMMENT ON TABLE mine_operation_effective_record IS '统计和查询使用的执行事实当前有效投影';
COMMENT ON COLUMN mine_operation_effective_record.operation_record_id IS '来源不可变执行记录唯一标识';
COMMENT ON COLUMN mine_operation_effective_record.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_operation_effective_record.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_operation_effective_record.shift_instance_id IS '所属班次实例唯一标识';
COMMENT ON COLUMN mine_operation_effective_record.work_order_id IS '所属作业单唯一标识';
COMMENT ON COLUMN mine_operation_effective_record.assignment_id IS '所属作业分配唯一标识';
COMMENT ON COLUMN mine_operation_effective_record.cycle_number IS '作业分配内趟次序号';
COMMENT ON COLUMN mine_operation_effective_record.work_type IS '有效投影作业类型';
COMMENT ON COLUMN mine_operation_effective_record.record_status IS '有效投影执行结果状态';
COMMENT ON COLUMN mine_operation_effective_record.effective IS '当前事实是否计入有效运营统计';
COMMENT ON COLUMN mine_operation_effective_record.vehicle_id IS '当前有效车辆唯一标识';
COMMENT ON COLUMN mine_operation_effective_record.driver_person_id IS '当前有效司机组织级人员标识';
COMMENT ON COLUMN mine_operation_effective_record.started_at IS '当前有效本趟开始时间';
COMMENT ON COLUMN mine_operation_effective_record.completed_at IS '当前有效本趟结束时间';
COMMENT ON COLUMN mine_operation_effective_record.distance_meters IS '当前有效运输距离，单位米';
COMMENT ON COLUMN mine_operation_effective_record.actual_tons IS '当前有效实际吨位，未知时为空';
COMMENT ON COLUMN mine_operation_effective_record.tons_source IS '当前有效吨位数据来源';
COMMENT ON COLUMN mine_operation_effective_record.correction_version IS '投影已应用的最新修正版本';
COMMENT ON COLUMN mine_operation_effective_record.projected_at IS '有效投影最近生成时间';

COMMENT ON TABLE mine_operation_outbox IS '运营事实同事务写入的可靠业务消息出站箱';
COMMENT ON COLUMN mine_operation_outbox.id IS '运营出站消息唯一标识';
COMMENT ON COLUMN mine_operation_outbox.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_operation_outbox.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_operation_outbox.aggregate_type IS '消息关联聚合根类型';
COMMENT ON COLUMN mine_operation_outbox.aggregate_id IS '消息关联聚合根唯一标识';
COMMENT ON COLUMN mine_operation_outbox.event_type IS '固定运营实时事件类型';
COMMENT ON COLUMN mine_operation_outbox.event_version IS '运营实时事件模式版本';
COMMENT ON COLUMN mine_operation_outbox.payload IS '待发布规范化事件载荷';
COMMENT ON COLUMN mine_operation_outbox.status IS '出站消息发布状态';
COMMENT ON COLUMN mine_operation_outbox.attempt_count IS '消息发布尝试次数';
COMMENT ON COLUMN mine_operation_outbox.next_attempt_at IS '允许下次领取的时间';
COMMENT ON COLUMN mine_operation_outbox.lease_token IS '本次发布随机租约令牌';
COMMENT ON COLUMN mine_operation_outbox.leased_until IS '本次发布租约到期时间';
COMMENT ON COLUMN mine_operation_outbox.last_error IS '最近一次固定长度发布错误摘要';
COMMENT ON COLUMN mine_operation_outbox.created_at IS '出站消息创建时间';
COMMENT ON COLUMN mine_operation_outbox.published_at IS '消息确认发布时间';

COMMENT ON FUNCTION mine_validate_shift_instance_definition() IS '校验班次实例使用活动模板及有效营业日期';
COMMENT ON FUNCTION mine_validate_work_order_window_and_release() IS '校验作业窗口及发布时冻结空间版本';
COMMENT ON FUNCTION mine_validate_work_order_assignment_window() IS '校验作业分配完整落在作业计划窗口内';
COMMENT ON FUNCTION mine_validate_operation_record_context() IS '校验执行事实与班次作业分配及冻结空间版本一致';
COMMENT ON FUNCTION mine_validate_operation_effective_source() IS '校验有效投影身份时间、来源事实及修正版本快照一致';
COMMENT ON FUNCTION mine_reject_closed_shift_change() IS '禁止修改或删除终态班次实例';
COMMENT ON FUNCTION mine_reject_released_work_order_snapshot_change() IS '禁止改写已发布作业单冻结计划和版本快照';
COMMENT ON FUNCTION mine_reject_operation_fact_change() IS '禁止修改或删除追加式运营执行事实';
