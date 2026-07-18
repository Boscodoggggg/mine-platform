-- 控制面快照使用 SOURCE_DEVICE 区分可信来源设备与普通业务设备条目。
ALTER TABLE location_snapshot_stage
    DROP CONSTRAINT ck_location_snapshot_stage_type,
    ADD CONSTRAINT ck_location_snapshot_stage_type CHECK (item_type IN (
        'SITE', 'VEHICLE', 'PERSON', 'LOCATION_TAG', 'SOURCE_DEVICE',
        'COORDINATE_SYSTEM', 'COORDINATE_TRANSFORM',
        'VEHICLE_DEVICE_BINDING', 'VEHICLE_TAG_BINDING',
        'PERSON_TAG_BINDING', 'PERSON_VEHICLE_BINDING'
    ));
