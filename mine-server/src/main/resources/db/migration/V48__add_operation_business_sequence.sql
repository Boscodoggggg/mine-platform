ALTER TABLE mine_operation_outbox
    ADD COLUMN business_sequence BIGINT;

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY site_id
               ORDER BY created_at, id
           ) AS business_sequence
    FROM mine_operation_outbox
)
UPDATE mine_operation_outbox outbox
SET business_sequence = ranked.business_sequence
FROM ranked
WHERE ranked.id = outbox.id;

CREATE TABLE mine_operation_site_sequence (
    site_id          UUID        NOT NULL,
    organization_id  UUID        NOT NULL,
    last_sequence    BIGINT      NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_operation_site_sequence PRIMARY KEY (site_id),
    CONSTRAINT uk_mine_operation_site_sequence_scope
        UNIQUE (site_id, organization_id),
    CONSTRAINT fk_mine_operation_site_sequence_site_scope
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT ck_mine_operation_site_sequence_value
        CHECK (last_sequence >= 0)
);

INSERT INTO mine_operation_site_sequence (
    site_id, organization_id, last_sequence, updated_at
)
SELECT site_id, organization_id, MAX(business_sequence), CURRENT_TIMESTAMP
FROM mine_operation_outbox
GROUP BY site_id, organization_id;

ALTER TABLE mine_operation_outbox
    ALTER COLUMN business_sequence SET NOT NULL,
    ADD CONSTRAINT uk_mine_operation_outbox_site_sequence
        UNIQUE (site_id, business_sequence),
    ADD CONSTRAINT ck_mine_operation_outbox_business_sequence
        CHECK (business_sequence > 0);

CREATE FUNCTION mine_assign_operation_business_sequence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO mine_operation_site_sequence (
        site_id, organization_id, last_sequence, updated_at
    ) VALUES (
        NEW.site_id, NEW.organization_id, 1, CURRENT_TIMESTAMP
    )
    ON CONFLICT (site_id) DO UPDATE
    SET last_sequence = mine_operation_site_sequence.last_sequence + 1,
        updated_at = CURRENT_TIMESTAMP
    WHERE mine_operation_site_sequence.organization_id = EXCLUDED.organization_id
    RETURNING last_sequence INTO NEW.business_sequence;

    IF NEW.business_sequence IS NULL THEN
        RAISE EXCEPTION '运营业务序号矿区与组织作用域不一致'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mine_operation_outbox_business_sequence
BEFORE INSERT ON mine_operation_outbox
FOR EACH ROW
EXECUTE FUNCTION mine_assign_operation_business_sequence();

COMMENT ON TABLE mine_operation_site_sequence IS '按矿区串行分配运营实时事件连续业务序号的持久计数器';
COMMENT ON COLUMN mine_operation_site_sequence.site_id IS '所属矿区唯一标识及计数器主键';
COMMENT ON COLUMN mine_operation_site_sequence.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_operation_site_sequence.last_sequence IS '本矿区最近已分配的运营业务序号';
COMMENT ON COLUMN mine_operation_site_sequence.updated_at IS '计数器最近更新时间';
COMMENT ON COLUMN mine_operation_outbox.business_sequence IS '矿区内连续递增且持久唯一的运营业务序号';
COMMENT ON FUNCTION mine_assign_operation_business_sequence() IS '在运营出站消息插入事务内按矿区串行分配连续业务序号';
