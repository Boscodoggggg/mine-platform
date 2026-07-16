-- 结束历史关系时只关闭生效区间，不再要求设备和设备组仍处于活动状态。
CREATE OR REPLACE FUNCTION dev_validate_device_group_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.effective_to IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM dev_device_group device_group
        JOIN dev_device device ON device.product_id = device_group.product_id
        WHERE device_group.id = NEW.group_id
          AND device.id = NEW.device_id
          AND device_group.status = 'ACTIVE'
          AND device.status <> 'ARCHIVED'
    ) THEN
        RAISE EXCEPTION '设备与设备组必须属于同一有效产品'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- 设备归档属于生命周期终态，数据库自动关闭全部当前设备组关系以防旁路写入留下脏数据。
CREATE FUNCTION dev_close_device_group_membership_on_archive()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ARCHIVED' AND OLD.status <> 'ARCHIVED' THEN
        UPDATE dev_device_group
           SET updated_at = CURRENT_TIMESTAMP,
               updated_by = NEW.updated_by,
               version = version + 1
         WHERE id IN (
             SELECT membership.group_id
               FROM dev_device_group_membership membership
              WHERE membership.device_id = NEW.id
                AND membership.effective_to IS NULL
         );
        UPDATE dev_device_group_membership
           SET effective_to = CURRENT_TIMESTAMP,
               ended_by = NEW.updated_by
         WHERE device_id = NEW.id
           AND effective_to IS NULL;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_dev_device_group_membership_close_on_device_archive
AFTER UPDATE OF status ON dev_device
FOR EACH ROW EXECUTE FUNCTION dev_close_device_group_membership_on_archive();

COMMENT ON FUNCTION dev_close_device_group_membership_on_archive()
IS '设备归档时自动结束全部当前设备组成员关系';
