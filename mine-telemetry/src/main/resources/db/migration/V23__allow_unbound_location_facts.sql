-- 有效标签尚未绑定车辆或人员时仍需保留标签级历史事实。
ALTER TABLE location_point
    DROP CONSTRAINT ck_location_point_runtime_fact,
    ADD CONSTRAINT ck_location_point_runtime_fact CHECK (
        organization_id IS NOT NULL
        AND site_id IS NOT NULL
        AND source_device_id IS NOT NULL
        AND tag_id IS NOT NULL
        AND source_coordinate_system_id IS NOT NULL
        AND source_x IS NOT NULL
        AND source_y IS NOT NULL
        AND target_coordinate_system_id IS NOT NULL
        AND transform_version_id IS NOT NULL
        AND boot_id IS NOT NULL
        AND sequence IS NOT NULL
    ) NOT VALID;
