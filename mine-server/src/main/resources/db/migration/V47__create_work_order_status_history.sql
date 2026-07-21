-- 为运营自动识别提供可审计的作业单发生时状态，并允许暂停作业保留运输循环运行态。

CREATE TABLE mine_work_order_status_history (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY,
    organization_id UUID        NOT NULL,
    site_id         UUID        NOT NULL,
    work_order_id   UUID        NOT NULL,
    status_version  BIGINT      NOT NULL,
    status          VARCHAR(16) NOT NULL,
    effective_from  TIMESTAMPTZ NOT NULL,
    effective_to    TIMESTAMPTZ,
    status_period   TSTZRANGE   GENERATED ALWAYS AS (
        tstzrange(effective_from, effective_to, '[)')
    ) STORED,
    changed_by      UUID,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_work_order_status_history PRIMARY KEY (id),
    CONSTRAINT uk_mine_work_order_status_history_version
        UNIQUE (work_order_id, status_version),
    CONSTRAINT fk_mine_work_order_status_history_order_scope
        FOREIGN KEY (work_order_id, organization_id, site_id)
        REFERENCES mine_work_order(id, organization_id, site_id),
    CONSTRAINT fk_mine_work_order_status_history_changed_by
        FOREIGN KEY (changed_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_work_order_status_history_version
        CHECK (status_version >= 0),
    CONSTRAINT ck_mine_work_order_status_history_status CHECK (
        status IN ('DRAFT', 'RELEASED', 'RUNNING', 'PAUSED',
                   'COMPLETED', 'CANCELLED', 'ABORTED')
    ),
    CONSTRAINT ck_mine_work_order_status_history_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_mine_work_order_status_history_period
        EXCLUDE USING gist (work_order_id WITH =, status_period WITH &&)
);

CREATE UNIQUE INDEX uk_mine_work_order_status_history_open
    ON mine_work_order_status_history(work_order_id)
    WHERE effective_to IS NULL;
CREATE INDEX idx_mine_work_order_status_history_site_time
    ON mine_work_order_status_history(site_id, work_order_id, effective_from DESC, id);

INSERT INTO mine_work_order_status_history (
    organization_id, site_id, work_order_id, status_version,
    status, effective_from, changed_by
)
SELECT organization_id,
       site_id,
       id,
       0,
       status,
       CASE status
           WHEN 'DRAFT' THEN created_at
           WHEN 'RELEASED' THEN COALESCE(released_at, updated_at)
           WHEN 'RUNNING' THEN CASE
               WHEN paused_at IS NOT NULL THEN updated_at
               ELSE COALESCE(started_at, updated_at)
           END
           WHEN 'PAUSED' THEN COALESCE(paused_at, updated_at)
           WHEN 'COMPLETED' THEN COALESCE(completed_at, updated_at)
           WHEN 'CANCELLED' THEN COALESCE(cancelled_at, updated_at)
           WHEN 'ABORTED' THEN COALESCE(aborted_at, updated_at)
       END,
       updated_by
FROM mine_work_order;

CREATE FUNCTION mine_capture_work_order_status_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transition_at TIMESTAMPTZ;
    next_version BIGINT;
    closed_count INTEGER;
BEGIN
    transition_at := CASE NEW.status
        WHEN 'DRAFT' THEN NEW.created_at
        WHEN 'RELEASED' THEN COALESCE(NEW.released_at, NEW.updated_at)
        WHEN 'RUNNING' THEN CASE
            WHEN TG_OP = 'UPDATE' AND OLD.status = 'PAUSED' THEN NEW.updated_at
            ELSE COALESCE(NEW.started_at, NEW.updated_at)
        END
        WHEN 'PAUSED' THEN COALESCE(NEW.paused_at, NEW.updated_at)
        WHEN 'COMPLETED' THEN COALESCE(NEW.completed_at, NEW.updated_at)
        WHEN 'CANCELLED' THEN COALESCE(NEW.cancelled_at, NEW.updated_at)
        WHEN 'ABORTED' THEN COALESCE(NEW.aborted_at, NEW.updated_at)
    END;

    IF TG_OP = 'INSERT' THEN
        INSERT INTO mine_work_order_status_history (
            organization_id, site_id, work_order_id, status_version,
            status, effective_from, changed_by
        ) VALUES (
            NEW.organization_id, NEW.site_id, NEW.id, 0,
            NEW.status, transition_at, COALESCE(NEW.updated_by, NEW.created_by)
        );
        RETURN NEW;
    END IF;

    SELECT COALESCE(MAX(status_version), -1) + 1
      INTO next_version
      FROM mine_work_order_status_history
     WHERE work_order_id = NEW.id;

    UPDATE mine_work_order_status_history
       SET effective_to = transition_at
     WHERE work_order_id = NEW.id
       AND effective_to IS NULL
       AND effective_from < transition_at;
    GET DIAGNOSTICS closed_count = ROW_COUNT;
    IF closed_count <> 1 THEN
        RAISE EXCEPTION '作业单状态发生时间必须晚于上一状态: %', NEW.id
            USING ERRCODE = '23514';
    END IF;

    INSERT INTO mine_work_order_status_history (
        organization_id, site_id, work_order_id, status_version,
        status, effective_from, changed_by
    ) VALUES (
        NEW.organization_id, NEW.site_id, NEW.id, next_version,
        NEW.status, transition_at, NEW.updated_by
    );
    RETURN NEW;
END
$$;

CREATE FUNCTION mine_protect_work_order_status_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '作业单状态历史不可删除'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id = NEW.id
       AND OLD.organization_id = NEW.organization_id
       AND OLD.site_id = NEW.site_id
       AND OLD.work_order_id = NEW.work_order_id
       AND OLD.status_version = NEW.status_version
       AND OLD.status = NEW.status
       AND OLD.effective_from = NEW.effective_from
       AND OLD.changed_by IS NOT DISTINCT FROM NEW.changed_by
       AND OLD.recorded_at = NEW.recorded_at
       AND OLD.effective_to IS NULL
       AND NEW.effective_to IS NOT NULL
       AND NEW.effective_to > OLD.effective_from THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '作业单状态历史仅允许关闭当前有效区间'
        USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER trg_mine_work_order_status_history_capture_insert
AFTER INSERT ON mine_work_order
FOR EACH ROW
EXECUTE FUNCTION mine_capture_work_order_status_history();

CREATE TRIGGER trg_mine_work_order_status_history_capture_update
AFTER UPDATE OF status ON mine_work_order
FOR EACH ROW
WHEN (OLD.status IS DISTINCT FROM NEW.status)
EXECUTE FUNCTION mine_capture_work_order_status_history();

CREATE TRIGGER trg_mine_work_order_status_history_protect
BEFORE UPDATE OR DELETE ON mine_work_order_status_history
FOR EACH ROW EXECUTE FUNCTION mine_protect_work_order_status_history();

CREATE OR REPLACE FUNCTION mine_validate_operation_runtime_context()
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
    IF work_order_status NOT IN ('RUNNING', 'PAUSED') OR shift_status <> 'OPEN' THEN
        RAISE EXCEPTION '运输循环运行态只能属于已开启班次的运行或暂停作业单'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON TABLE mine_work_order_status_history IS '作业单生命周期状态历史，用于按业务发生时间判定运行或暂停状态';
COMMENT ON COLUMN mine_work_order_status_history.id IS '状态历史数据库内部唯一标识';
COMMENT ON COLUMN mine_work_order_status_history.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_work_order_status_history.site_id IS '所属矿区唯一标识';
COMMENT ON COLUMN mine_work_order_status_history.work_order_id IS '所属作业单唯一标识';
COMMENT ON COLUMN mine_work_order_status_history.status_version IS '作业单内单调递增的状态历史版本';
COMMENT ON COLUMN mine_work_order_status_history.status IS '该有效时间区间内的作业单状态';
COMMENT ON COLUMN mine_work_order_status_history.effective_from IS '状态生效业务时间，包含该时刻';
COMMENT ON COLUMN mine_work_order_status_history.effective_to IS '状态失效业务时间，不包含该时刻，为空表示当前状态';
COMMENT ON COLUMN mine_work_order_status_history.status_period IS '数据库生成的左闭右开状态有效时间区间';
COMMENT ON COLUMN mine_work_order_status_history.changed_by IS '执行状态变更的用户标识';
COMMENT ON COLUMN mine_work_order_status_history.recorded_at IS '状态历史落库时间';
