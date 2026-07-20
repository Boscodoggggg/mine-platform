-- 强化班次、作业单和运输循环的跨聚合生命周期一致性。

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM mine_shift_instance shift_instance
        WHERE shift_instance.supervisor_person_id IS NOT NULL
          AND shift_instance.status IN ('PLANNED', 'OPEN')
          AND NOT EXISTS (
              SELECT 1
              FROM mine_person person
              JOIN mine_person_site_assignment site_assignment
                ON site_assignment.person_id = person.id
               AND site_assignment.organization_id = person.organization_id
               AND site_assignment.site_id = shift_instance.site_id
               AND site_assignment.valid_period @> tstzrange(
                    shift_instance.planned_start_at,
                    shift_instance.planned_end_at,
                    '[)')
              WHERE person.id = shift_instance.supervisor_person_id
                AND person.organization_id = shift_instance.organization_id
                AND person.status = 'ACTIVE'
                AND person.person_type = 'SUPERVISOR'
          )
    ) THEN
        RAISE EXCEPTION 'V45 升级失败：存在不满足完整矿区任职的班次负责人'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM mine_shift_instance shift_instance
        JOIN mine_work_order work_order
          ON work_order.shift_instance_id = shift_instance.id
         AND work_order.organization_id = shift_instance.organization_id
         AND work_order.site_id = shift_instance.site_id
        WHERE shift_instance.status IN ('CLOSED', 'CANCELLED')
          AND work_order.status NOT IN ('COMPLETED', 'CANCELLED', 'ABORTED')
    ) THEN
        RAISE EXCEPTION 'V45 升级失败：终态班次存在未终结作业单'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM mine_operation_runtime_state runtime_state
        JOIN mine_work_order work_order
          ON work_order.id = runtime_state.work_order_id
         AND work_order.organization_id = runtime_state.organization_id
         AND work_order.site_id = runtime_state.site_id
        JOIN mine_shift_instance shift_instance
          ON shift_instance.id = work_order.shift_instance_id
         AND shift_instance.organization_id = work_order.organization_id
         AND shift_instance.site_id = work_order.site_id
        WHERE work_order.status NOT IN ('RUNNING', 'PAUSED')
           OR shift_instance.status <> 'OPEN'
    ) THEN
        RAISE EXCEPTION 'V45 升级失败：终态作业单遗留运输循环运行态，或运行态不属于开放班次的运行作业'
            USING ERRCODE = '23514';
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION mine_validate_work_order_window_and_release()
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
      AND site_id = NEW.site_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    IF NEW.planned_start_at < shift_row.planned_start_at
       OR NEW.planned_end_at > shift_row.planned_end_at THEN
        RAISE EXCEPTION '作业单计划窗口必须完整落在班次计划窗口内'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status IN ('DRAFT', 'RELEASED')
       AND shift_row.status NOT IN ('PLANNED', 'OPEN') THEN
        RAISE EXCEPTION '已关闭或已取消班次不能新建或发布作业单'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.status IN ('RUNNING', 'PAUSED')
       AND shift_row.status <> 'OPEN' THEN
        RAISE EXCEPTION '只有已开启班次允许作业单进入运行状态'
            USING ERRCODE = '23514';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.status IS DISTINCT FROM OLD.status)
       AND NEW.status IN ('COMPLETED', 'ABORTED')
       AND shift_row.status <> 'OPEN' THEN
        RAISE EXCEPTION '只有已开启班次允许作业单完成或异常终止'
            USING ERRCODE = '23514';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.status IS DISTINCT FROM OLD.status)
       AND NEW.status = 'CANCELLED'
       AND shift_row.status NOT IN ('PLANNED', 'OPEN') THEN
        RAISE EXCEPTION '只有计划中或已开启班次允许取消作业单'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE'
       AND NEW.status IS DISTINCT FROM OLD.status
       AND NEW.status IN ('COMPLETED', 'CANCELLED', 'ABORTED')
       AND EXISTS (
            SELECT 1
            FROM mine_operation_runtime_state runtime_state
            WHERE runtime_state.organization_id = NEW.organization_id
              AND runtime_state.site_id = NEW.site_id
              AND runtime_state.work_order_id = NEW.id
       ) THEN
        RAISE EXCEPTION '作业单进入终态前必须先固化并清理运输循环运行态'
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

CREATE OR REPLACE FUNCTION mine_reject_closed_shift_change()
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
    IF NEW.status IN ('CLOSED', 'CANCELLED') AND (
        EXISTS (
            SELECT 1
            FROM mine_work_order work_order
            WHERE work_order.organization_id = NEW.organization_id
              AND work_order.site_id = NEW.site_id
              AND work_order.shift_instance_id = NEW.id
              AND work_order.status NOT IN ('COMPLETED', 'CANCELLED', 'ABORTED')
        )
        OR EXISTS (
            SELECT 1
            FROM mine_operation_runtime_state runtime_state
            JOIN mine_work_order work_order
              ON work_order.id = runtime_state.work_order_id
             AND work_order.organization_id = runtime_state.organization_id
             AND work_order.site_id = runtime_state.site_id
            WHERE work_order.organization_id = NEW.organization_id
              AND work_order.site_id = NEW.site_id
              AND work_order.shift_instance_id = NEW.id
        )
    ) THEN
        RAISE EXCEPTION '班次仍有未终结作业或运输循环运行态'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_operation_runtime_context()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    observed_shift_id UUID;
    work_order_status VARCHAR(16);
    shift_status VARCHAR(16);
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT work_order.shift_instance_id
        INTO observed_shift_id
        FROM mine_work_order work_order
        WHERE work_order.id = NEW.work_order_id
          AND work_order.organization_id = NEW.organization_id
          AND work_order.site_id = NEW.site_id;
        IF NOT FOUND THEN
            RETURN NEW;
        END IF;

        SELECT shift_instance.status
        INTO shift_status
        FROM mine_shift_instance shift_instance
        WHERE shift_instance.id = observed_shift_id
          AND shift_instance.organization_id = NEW.organization_id
          AND shift_instance.site_id = NEW.site_id
        FOR UPDATE;
        IF NOT FOUND THEN
            RETURN NEW;
        END IF;

        SELECT work_order.status
        INTO work_order_status
        FROM mine_work_order work_order
        WHERE work_order.id = NEW.work_order_id
          AND work_order.organization_id = NEW.organization_id
          AND work_order.site_id = NEW.site_id
          AND work_order.shift_instance_id = observed_shift_id
        FOR UPDATE;
        IF NOT FOUND THEN
            RETURN NEW;
        END IF;
    ELSE
        SELECT work_order.status, shift_instance.status
        INTO work_order_status, shift_status
        FROM mine_work_order work_order
        JOIN mine_shift_instance shift_instance
          ON shift_instance.id = work_order.shift_instance_id
         AND shift_instance.organization_id = work_order.organization_id
         AND shift_instance.site_id = work_order.site_id
        WHERE work_order.id = NEW.work_order_id
          AND work_order.organization_id = NEW.organization_id
          AND work_order.site_id = NEW.site_id;
        IF NOT FOUND THEN
            RETURN NEW;
        END IF;
    END IF;
    IF work_order_status <> 'RUNNING' OR shift_status <> 'OPEN' THEN
        RAISE EXCEPTION '运输循环运行态只能属于已开启班次的运行作业单'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_validate_shift_supervisor_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.supervisor_person_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM mine_person person
        JOIN mine_person_site_assignment site_assignment
          ON site_assignment.person_id = person.id
         AND site_assignment.organization_id = person.organization_id
         AND site_assignment.site_id = NEW.site_id
         AND site_assignment.valid_period @>
             tstzrange(NEW.planned_start_at, NEW.planned_end_at, '[)')
        WHERE person.id = NEW.supervisor_person_id
          AND person.organization_id = NEW.organization_id
          AND person.status = 'ACTIVE'
          AND person.person_type = 'SUPERVISOR'
    ) THEN
        RAISE EXCEPTION '班次负责人必须在完整班次期间于当前矿区在职'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER trg_mine_work_order_validate_window ON mine_work_order;

CREATE TRIGGER trg_mine_work_order_validate_window
BEFORE INSERT OR UPDATE OF
    shift_instance_id, organization_id, site_id, status,
    planned_start_at, planned_end_at, released_at,
    route_version_id, load_geofence_version_id, unload_geofence_version_id
ON mine_work_order
FOR EACH ROW EXECUTE FUNCTION mine_validate_work_order_window_and_release();

CREATE TRIGGER trg_mine_shift_instance_validate_supervisor
BEFORE INSERT OR UPDATE OF
    organization_id, site_id, planned_start_at, planned_end_at, supervisor_person_id
ON mine_shift_instance
FOR EACH ROW EXECUTE FUNCTION mine_validate_shift_supervisor_scope();

CREATE TRIGGER trg_mine_operation_runtime_validate_context
BEFORE INSERT OR UPDATE ON mine_operation_runtime_state
FOR EACH ROW EXECUTE FUNCTION mine_validate_operation_runtime_context();
