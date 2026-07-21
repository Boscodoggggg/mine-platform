-- 扩展定位运行快照，使其能够原子下发已发布的途径点、路线和电子围栏版本。
ALTER TABLE mine_location_runtime_snapshot
    DROP CONSTRAINT ck_mine_location_runtime_snapshot_reason,
    ADD CONSTRAINT ck_mine_location_runtime_snapshot_reason
        CHECK (change_reason IN (
            'SITE_CHANGE', 'ASSET_CHANGE', 'COORDINATE_CHANGE',
            'BINDING_SWITCH', 'SPATIAL_CHANGE'
        ));

ALTER TABLE mine_location_runtime_snapshot_item
    DROP CONSTRAINT ck_mine_location_runtime_snapshot_item_type,
    ADD CONSTRAINT ck_mine_location_runtime_snapshot_item_type
        CHECK (item_type IN (
            'SITE', 'VEHICLE', 'PERSON', 'LOCATION_TAG', 'SOURCE_DEVICE',
            'COORDINATE_SYSTEM', 'COORDINATE_TRANSFORM',
            'VEHICLE_DEVICE_BINDING', 'VEHICLE_TAG_BINDING',
            'PERSON_TAG_BINDING', 'PERSON_VEHICLE_BINDING',
            'WAYPOINT_VERSION', 'ROUTE_VERSION', 'GEOFENCE_VERSION'
        ));

-- 控制面与运行面统一采用三维坐标边界，避免发布成功后被 Telemetry 永久拒绝。
CREATE FUNCTION mine_validate_spatial_height_bounds()
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
      AND site_id = NEW.site_id
      AND unified
      AND status = 'ACTIVE'
    FOR NO KEY UPDATE;
    IF NOT FOUND OR (
        NEW.min_z IS NOT NULL
        AND coordinate_row.min_z IS NOT NULL
        AND (NEW.min_z < coordinate_row.min_z OR NEW.max_z > coordinate_row.max_z)
    ) THEN
        RAISE EXCEPTION '空间高度区间超出统一坐标系高程边界'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_waypoint_coordinate_bounds_on_write()
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
      AND site_id = NEW.site_id
      AND unified
      AND status = 'ACTIVE'
    FOR NO KEY UPDATE;
    IF NOT FOUND
       OR NEW.x < coordinate_row.min_x OR NEW.x > coordinate_row.max_x
       OR NEW.y < coordinate_row.min_y OR NEW.y > coordinate_row.max_y
       OR NEW.z IS NOT NULL AND coordinate_row.min_z IS NOT NULL
          AND (NEW.z < coordinate_row.min_z OR NEW.z > coordinate_row.max_z) THEN
        RAISE EXCEPTION '途径点圆心超出统一坐标系边界'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_geofence_vertices_on_publish()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    coordinate_row mine_coordinate_system%ROWTYPE;
BEGIN
    IF NEW.status <> 'PUBLISHED' OR OLD.status = 'PUBLISHED' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO coordinate_row
    FROM mine_coordinate_system
    WHERE id = NEW.coordinate_system_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id
      AND unified
      AND status = 'ACTIVE'
    FOR NO KEY UPDATE;
    IF NOT FOUND OR EXISTS (
        SELECT 1
        FROM mine_geofence_vertex vertex
        WHERE vertex.geofence_version_id = NEW.id
          AND (
              vertex.x < coordinate_row.min_x OR vertex.x > coordinate_row.max_x
              OR vertex.y < coordinate_row.min_y OR vertex.y > coordinate_row.max_y
          )
    ) THEN
        RAISE EXCEPTION '围栏顶点超出统一坐标系边界'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_lock_spatial_coordinate_on_publish()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'PUBLISHED' OR OLD.status = 'PUBLISHED' THEN
        RETURN NEW;
    END IF;
    PERFORM 1
    FROM mine_coordinate_system
    WHERE id = NEW.coordinate_system_id
      AND organization_id = NEW.organization_id
      AND site_id = NEW.site_id
      AND unified
      AND status = 'ACTIVE'
    FOR NO KEY UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION '空间版本发布时统一坐标系必须保持活动'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_protect_published_spatial_coordinate_system()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        NEW.coordinate_type, NEW.unit, NEW.epsg_code, NEW.unified,
        NEW.min_x, NEW.max_x, NEW.min_y, NEW.max_y,
        NEW.min_z, NEW.max_z, NEW.status
    ) IS DISTINCT FROM ROW(
        OLD.coordinate_type, OLD.unit, OLD.epsg_code, OLD.unified,
        OLD.min_x, OLD.max_x, OLD.min_y, OLD.max_y,
        OLD.min_z, OLD.max_z, OLD.status
    ) AND EXISTS (
        SELECT 1 FROM mine_waypoint_version
        WHERE coordinate_system_id = OLD.id AND status = 'PUBLISHED'
        UNION ALL
        SELECT 1 FROM mine_route_version
        WHERE coordinate_system_id = OLD.id AND status = 'PUBLISHED'
        UNION ALL
        SELECT 1 FROM mine_geofence_version
        WHERE coordinate_system_id = OLD.id AND status = 'PUBLISHED'
    ) THEN
        RAISE EXCEPTION '已发布空间版本引用的坐标系语义和状态不可修改'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mine_waypoint_version_height_bounds
BEFORE INSERT OR UPDATE ON mine_waypoint_version
FOR EACH ROW EXECUTE FUNCTION mine_validate_spatial_height_bounds();

CREATE TRIGGER trg_mine_geofence_version_height_bounds
BEFORE INSERT OR UPDATE ON mine_geofence_version
FOR EACH ROW EXECUTE FUNCTION mine_validate_spatial_height_bounds();

CREATE TRIGGER trg_mine_waypoint_version_coordinate_recheck
BEFORE INSERT OR UPDATE ON mine_waypoint_version
FOR EACH ROW EXECUTE FUNCTION mine_validate_waypoint_coordinate_bounds_on_write();

CREATE TRIGGER trg_mine_geofence_version_coordinate_recheck
BEFORE UPDATE OF status ON mine_geofence_version
FOR EACH ROW EXECUTE FUNCTION mine_validate_geofence_vertices_on_publish();

CREATE TRIGGER trg_mine_waypoint_version_coordinate_publish_lock
BEFORE UPDATE OF status ON mine_waypoint_version
FOR EACH ROW EXECUTE FUNCTION mine_lock_spatial_coordinate_on_publish();

CREATE TRIGGER trg_mine_route_version_coordinate_publish_lock
BEFORE UPDATE OF status ON mine_route_version
FOR EACH ROW EXECUTE FUNCTION mine_lock_spatial_coordinate_on_publish();

CREATE TRIGGER trg_mine_geofence_version_coordinate_publish_lock
BEFORE UPDATE OF status ON mine_geofence_version
FOR EACH ROW EXECUTE FUNCTION mine_lock_spatial_coordinate_on_publish();

CREATE TRIGGER trg_mine_coordinate_system_spatial_reference_guard
BEFORE UPDATE ON mine_coordinate_system
FOR EACH ROW EXECUTE FUNCTION mine_protect_published_spatial_coordinate_system();

-- 归档与路线发布按途径点稳定标识串行，避免生成缺少路线引用的运行快照。
CREATE FUNCTION mine_guard_referenced_waypoint_archive()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'ARCHIVED' OR OLD.status = 'ARCHIVED' THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'mine-waypoint-runtime:' || NEW.id::TEXT, 0));
    IF EXISTS (
        SELECT 1
        FROM mine_waypoint_version waypoint_version
        JOIN mine_route_version_waypoint route_waypoint
          ON route_waypoint.waypoint_version_id = waypoint_version.id
         AND route_waypoint.organization_id = waypoint_version.organization_id
         AND route_waypoint.site_id = waypoint_version.site_id
        JOIN mine_route_version route_version
          ON route_version.id = route_waypoint.route_version_id
         AND route_version.organization_id = route_waypoint.organization_id
         AND route_version.site_id = route_waypoint.site_id
        JOIN mine_route route
          ON route.id = route_version.route_id
         AND route.organization_id = route_version.organization_id
         AND route.site_id = route_version.site_id
        WHERE waypoint_version.waypoint_id = NEW.id
          AND route_version.status = 'PUBLISHED'
          AND route.status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION '活动已发布路线仍在引用该途径点，禁止归档'
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_require_active_route_waypoint_objects()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'PUBLISHED' OR OLD.status = 'PUBLISHED' THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'mine-waypoint-runtime:' || referenced.waypoint_id::TEXT, 0))
    FROM (
        SELECT DISTINCT waypoint_version.waypoint_id
        FROM mine_route_version_waypoint route_waypoint
        JOIN mine_waypoint_version waypoint_version
          ON waypoint_version.id = route_waypoint.waypoint_version_id
         AND waypoint_version.organization_id = route_waypoint.organization_id
         AND waypoint_version.site_id = route_waypoint.site_id
        WHERE route_waypoint.route_version_id = NEW.id
        ORDER BY waypoint_version.waypoint_id
    ) referenced;
    IF EXISTS (
        SELECT 1
        FROM mine_route_version_waypoint route_waypoint
        JOIN mine_waypoint_version waypoint_version
          ON waypoint_version.id = route_waypoint.waypoint_version_id
         AND waypoint_version.organization_id = route_waypoint.organization_id
         AND waypoint_version.site_id = route_waypoint.site_id
        JOIN mine_waypoint waypoint
          ON waypoint.id = waypoint_version.waypoint_id
         AND waypoint.organization_id = waypoint_version.organization_id
         AND waypoint.site_id = waypoint_version.site_id
        WHERE route_waypoint.route_version_id = NEW.id
          AND waypoint.status <> 'ACTIVE'
    ) THEN
        RAISE EXCEPTION '路线发布只能引用活动途径点的已发布版本'
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mine_waypoint_archive_reference_guard
BEFORE UPDATE OF status ON mine_waypoint
FOR EACH ROW EXECUTE FUNCTION mine_guard_referenced_waypoint_archive();

CREATE TRIGGER trg_mine_route_version_active_waypoint_guard
BEFORE UPDATE OF status ON mine_route_version
FOR EACH ROW EXECUTE FUNCTION mine_require_active_route_waypoint_objects();

COMMENT ON FUNCTION mine_guard_referenced_waypoint_archive()
IS '拒绝归档仍被活动已发布路线引用的稳定途径点';
COMMENT ON FUNCTION mine_require_active_route_waypoint_objects()
IS '路线发布时锁定并校验全部稳定途径点仍处于活动状态';
COMMENT ON FUNCTION mine_validate_spatial_height_bounds()
IS '写入和发布途径点及围栏版本时校验高程边界';
COMMENT ON FUNCTION mine_validate_waypoint_coordinate_bounds_on_write()
IS '写入和发布途径点版本时重新校验圆心平面与高程坐标';
COMMENT ON FUNCTION mine_validate_geofence_vertices_on_publish()
IS '发布围栏版本时按最新统一坐标边界重扫全部顶点';
COMMENT ON FUNCTION mine_lock_spatial_coordinate_on_publish()
IS '空间版本发布时锁定并校验活动统一坐标系';
COMMENT ON FUNCTION mine_protect_published_spatial_coordinate_system()
IS '阻止修改或归档已被发布空间版本引用的坐标系';
COMMENT ON TRIGGER trg_mine_waypoint_archive_reference_guard ON mine_waypoint
IS '防止途径点归档破坏活动路线运行快照引用';
COMMENT ON TRIGGER trg_mine_route_version_active_waypoint_guard ON mine_route_version
IS '防止路线发布引用已归档途径点';
COMMENT ON TRIGGER trg_mine_waypoint_version_height_bounds ON mine_waypoint_version
IS '写入和发布途径点版本时校验高度边界';
COMMENT ON TRIGGER trg_mine_geofence_version_height_bounds ON mine_geofence_version
IS '写入和发布围栏版本时校验高度边界';
COMMENT ON TRIGGER trg_mine_waypoint_version_coordinate_recheck ON mine_waypoint_version
IS '写入和发布途径点版本时校验圆心坐标边界';
COMMENT ON TRIGGER trg_mine_geofence_version_coordinate_recheck ON mine_geofence_version
IS '发布围栏版本时重新校验全部顶点坐标边界';
COMMENT ON TRIGGER trg_mine_waypoint_version_coordinate_publish_lock ON mine_waypoint_version
IS '途径点版本发布时锁定统一坐标系';
COMMENT ON TRIGGER trg_mine_route_version_coordinate_publish_lock ON mine_route_version
IS '路线版本发布时锁定统一坐标系';
COMMENT ON TRIGGER trg_mine_geofence_version_coordinate_publish_lock ON mine_geofence_version
IS '围栏版本发布时锁定统一坐标系';
COMMENT ON TRIGGER trg_mine_coordinate_system_spatial_reference_guard ON mine_coordinate_system
IS '防止坐标系变更破坏已发布空间版本';
