-- 命令原因、作业单和取消信息用于现场追责；仅允许在可靠出站事件尚未被发布时取消。
ALTER TABLE cmd_instance
    ADD COLUMN work_order_no VARCHAR(128),
    ADD COLUMN cancel_reason VARCHAR(500),
    ADD COLUMN cancelled_by UUID;

ALTER TABLE cmd_instance
    ADD CONSTRAINT fk_cmd_instance_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES sys_user (id),
    ADD CONSTRAINT ck_cmd_instance_work_order CHECK (work_order_no IS NULL OR btrim(work_order_no) <> ''),
    ADD CONSTRAINT ck_cmd_instance_cancel_reason CHECK (cancel_reason IS NULL OR btrim(cancel_reason) <> ''),
    ADD CONSTRAINT ck_cmd_instance_cancel_fields CHECK (
        (status = 'CANCELLED' AND cancel_reason IS NOT NULL AND cancelled_by IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancel_reason IS NULL AND cancelled_by IS NULL)
    );

COMMENT ON COLUMN cmd_instance.work_order_no IS '关联矿区作业单号或调度任务号';
COMMENT ON COLUMN cmd_instance.cancel_reason IS '管理员取消未发送命令的原因';
COMMENT ON COLUMN cmd_instance.cancelled_by IS '执行取消操作的管理员标识';

INSERT INTO sys_menu (
    id, parent_id, name, type, permission, sort_order, visible, protected_node
) VALUES (
    '019b0000-0000-7000-8000-000000000313',
    '019b0000-0000-7000-8000-000000000310',
    '取消待发送命令', 'BUTTON', 'command:instance:cancel', 30, FALSE, TRUE
);
