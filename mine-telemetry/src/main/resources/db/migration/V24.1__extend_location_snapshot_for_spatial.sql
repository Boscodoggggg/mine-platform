-- 允许 Telemetry 暂存空间控制面发布的三类不可变运行版本。
ALTER TABLE location_snapshot_stage
    DROP CONSTRAINT ck_location_snapshot_stage_type,
    ADD CONSTRAINT ck_location_snapshot_stage_type CHECK (item_type IN (
        'SITE', 'VEHICLE', 'PERSON', 'LOCATION_TAG', 'SOURCE_DEVICE',
        'COORDINATE_SYSTEM', 'COORDINATE_TRANSFORM',
        'VEHICLE_DEVICE_BINDING', 'VEHICLE_TAG_BINDING',
        'PERSON_TAG_BINDING', 'PERSON_VEHICLE_BINDING',
        'WAYPOINT_VERSION', 'ROUTE_VERSION', 'GEOFENCE_VERSION'
    ));

-- 10,000 个路线节点的规范化 JSON 约 1.33 MiB，保留确定的安全余量。
ALTER TABLE location_snapshot_stage
    DROP CONSTRAINT ck_location_snapshot_stage_content,
    ADD CONSTRAINT ck_location_snapshot_stage_content CHECK (
        jsonb_typeof(content_json) = 'object'
        AND pg_column_size(content_json) <= 2097152
        AND content_hash ~ '^sha256:[0-9a-f]{64}$'
        AND snapshot_content_hash ~ '^sha256:[0-9a-f]{64}$'
    );
