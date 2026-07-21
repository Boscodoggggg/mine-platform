-- 将人员规范为组织级主档，并以带时间区间的任职关系关联矿区。
ALTER TABLE mine_person
    ADD CONSTRAINT uk_mine_person_organization_scope UNIQUE (id, organization_id);

CREATE TABLE mine_person_site_assignment (
    id               UUID         NOT NULL,
    organization_id  UUID         NOT NULL,
    site_id           UUID         NOT NULL,
    person_id         UUID         NOT NULL,
    valid_from        TIMESTAMPTZ  NOT NULL,
    valid_to          TIMESTAMPTZ,
    valid_period      TSTZRANGE    GENERATED ALWAYS AS (
        tstzrange(valid_from, valid_to, '[)')
    ) STORED,
    reason            VARCHAR(500) NOT NULL DEFAULT 'INITIAL_EMPLOYMENT',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        UUID,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_mine_person_site_assignment PRIMARY KEY (id),
    CONSTRAINT uk_mine_person_site_assignment_scope
        UNIQUE (id, organization_id, site_id),
    CONSTRAINT fk_mine_person_site_assignment_person_organization
        FOREIGN KEY (person_id, organization_id)
        REFERENCES mine_person(id, organization_id),
    CONSTRAINT fk_mine_person_site_assignment_site_organization
        FOREIGN KEY (site_id, organization_id)
        REFERENCES mine_site(id, organization_id),
    CONSTRAINT fk_mine_person_site_assignment_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_mine_person_site_assignment_updated_by
        FOREIGN KEY (updated_by) REFERENCES sys_user(id) ON DELETE SET NULL,
    CONSTRAINT ck_mine_person_site_assignment_period
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mine_person_site_assignment_reason
        CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_mine_person_site_assignment_audit
        CHECK (updated_at >= created_at AND version >= 0),
    CONSTRAINT ex_mine_person_site_assignment_person
        EXCLUDE USING gist (person_id WITH =, valid_period WITH &&)
        DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX idx_mine_person_site_assignment_site_time
    ON mine_person_site_assignment(site_id, valid_from DESC, person_id);
CREATE INDEX idx_mine_person_site_assignment_person_time
    ON mine_person_site_assignment(person_id, valid_from DESC, id);

INSERT INTO mine_person_site_assignment (
    id, organization_id, site_id, person_id, valid_from, valid_to,
    reason, created_at, created_by, updated_at, updated_by, version
)
SELECT gen_random_uuid(), person.organization_id, person.site_id, person.id,
       person.employment_from::TIMESTAMP AT TIME ZONE site.timezone,
       CASE
           WHEN person.employment_to IS NULL THEN NULL
           ELSE (person.employment_to + 1)::TIMESTAMP AT TIME ZONE site.timezone
       END,
       'MIGRATED_INITIAL_EMPLOYMENT', person.created_at, person.created_by,
       person.updated_at, person.updated_by, 0
FROM mine_person person
JOIN mine_site site
  ON site.id = person.site_id
 AND site.organization_id = person.organization_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM (
            SELECT organization_id, site_id, person_id, valid_period
            FROM mine_person_tag_binding
            UNION ALL
            SELECT organization_id, site_id, person_id, valid_period
            FROM mine_person_vehicle_binding
        ) binding
        WHERE NOT EXISTS (
            SELECT 1
            FROM mine_person_site_assignment assignment
            WHERE assignment.organization_id = binding.organization_id
              AND assignment.site_id = binding.site_id
              AND assignment.person_id = binding.person_id
              AND assignment.valid_period @> binding.valid_period
        )
    ) THEN
        RAISE EXCEPTION '既有人员绑定未被矿区任职区间完整覆盖，拒绝升级'
            USING ERRCODE = '23514';
    END IF;
END
$$;

ALTER TABLE mine_driver_qualification
    DROP CONSTRAINT fk_mine_driver_qualification_person_scope,
    ADD CONSTRAINT fk_mine_driver_qualification_person_organization
        FOREIGN KEY (person_id, organization_id)
        REFERENCES mine_person(id, organization_id),
    DROP COLUMN site_id;

ALTER TABLE mine_person_tag_binding
    DROP CONSTRAINT fk_mine_person_tag_binding_person_scope,
    ADD CONSTRAINT fk_mine_person_tag_binding_person_organization
        FOREIGN KEY (person_id, organization_id)
        REFERENCES mine_person(id, organization_id);

ALTER TABLE mine_person_vehicle_binding
    DROP CONSTRAINT fk_mine_person_vehicle_binding_person_scope,
    ADD CONSTRAINT fk_mine_person_vehicle_binding_person_organization
        FOREIGN KEY (person_id, organization_id)
        REFERENCES mine_person(id, organization_id);

DROP INDEX idx_mine_person_site_status_name;

ALTER TABLE mine_person
    DROP CONSTRAINT fk_mine_person_site_organization,
    DROP CONSTRAINT uk_mine_person_scope,
    DROP CONSTRAINT ck_mine_person_ciphertext,
    DROP CONSTRAINT ck_mine_person_employment_period,
    DROP COLUMN site_id,
    DROP COLUMN employment_from,
    DROP COLUMN employment_to,
    ADD CONSTRAINT ck_mine_person_ciphertext CHECK (
        phone_ciphertext ~ '^v[12]:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'
        AND certificate_ciphertext ~ '^v[12]:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'
        AND btrim(phone_masked) <> ''
        AND btrim(certificate_masked) <> ''
    );

CREATE INDEX idx_mine_person_organization_status_name
    ON mine_person(organization_id, status, name, id);

CREATE TABLE mine_sensitive_data_key_guard (
    purpose                    VARCHAR(64)  NOT NULL,
    verification_ciphertext    TEXT         NOT NULL,
    legacy_upgrade_completed   BOOLEAN      NOT NULL DEFAULT FALSE,
    completed_at               TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_mine_sensitive_data_key_guard PRIMARY KEY (purpose),
    CONSTRAINT ck_mine_sensitive_data_key_guard_purpose
        CHECK (btrim(purpose) <> ''),
    CONSTRAINT ck_mine_sensitive_data_key_guard_ciphertext
        CHECK (verification_ciphertext ~ '^v2:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$'),
    CONSTRAINT ck_mine_sensitive_data_key_guard_completion CHECK (
        (NOT legacy_upgrade_completed AND completed_at IS NULL)
        OR (legacy_upgrade_completed AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_mine_sensitive_data_key_guard_audit
        CHECK (updated_at >= created_at)
);

CREATE FUNCTION mine_enforce_person_ciphertext_upgrade()
RETURNS TRIGGER
LANGUAGE PLPGSQL
AS $$
BEGIN
    IF NEW.phone_ciphertext LIKE 'v1:%'
       OR NEW.certificate_ciphertext LIKE 'v1:%' THEN
        PERFORM pg_advisory_xact_lock(-487311842020);
        IF EXISTS (
            SELECT 1
            FROM mine_sensitive_data_key_guard guard
            WHERE guard.purpose = 'PERSON_DATA'
              AND guard.legacy_upgrade_completed
        ) THEN
            RAISE EXCEPTION '人员敏感字段升级完成后禁止写入一版密文'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mine_person_ciphertext_upgrade
BEFORE INSERT OR UPDATE ON mine_person
FOR EACH ROW
EXECUTE FUNCTION mine_enforce_person_ciphertext_upgrade();

-- 所有人员任职和绑定写操作先争用同一事务锁，消除跨表并发写偏差。
CREATE FUNCTION mine_lock_person_timeline()
RETURNS TRIGGER
LANGUAGE PLPGSQL
AS $$
DECLARE
    old_person_id UUID;
    new_person_id UUID;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_person_id := OLD.person_id;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_person_id := NEW.person_id;
    END IF;

    IF TG_TABLE_NAME = 'mine_person_site_assignment'
       AND TG_OP = 'UPDATE'
       AND (OLD.organization_id, OLD.site_id, OLD.person_id)
           IS DISTINCT FROM (NEW.organization_id, NEW.site_id, NEW.person_id) THEN
        RAISE EXCEPTION '人员矿区任职的组织、矿区和人员不可原地变更'
            USING ERRCODE = '23514';
    END IF;

    IF old_person_id IS NULL OR old_person_id = new_person_id THEN
        PERFORM pg_advisory_xact_lock(
            hashtextextended(COALESCE(old_person_id, new_person_id)::TEXT, 487311842019)
        );
    ELSIF new_person_id IS NULL THEN
        PERFORM pg_advisory_xact_lock(
            hashtextextended(old_person_id::TEXT, 487311842019)
        );
    ELSIF old_person_id < new_person_id THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(old_person_id::TEXT, 487311842019));
        PERFORM pg_advisory_xact_lock(hashtextextended(new_person_id::TEXT, 487311842019));
    ELSE
        PERFORM pg_advisory_xact_lock(hashtextextended(new_person_id::TEXT, 487311842019));
        PERFORM pg_advisory_xact_lock(hashtextextended(old_person_id::TEXT, 487311842019));
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mine_person_site_assignment_timeline_lock
BEFORE INSERT OR UPDATE OR DELETE ON mine_person_site_assignment
FOR EACH ROW
EXECUTE FUNCTION mine_lock_person_timeline();

CREATE TRIGGER trg_mine_person_tag_timeline_lock
BEFORE INSERT OR UPDATE ON mine_person_tag_binding
FOR EACH ROW
EXECUTE FUNCTION mine_lock_person_timeline();

CREATE TRIGGER trg_mine_person_vehicle_timeline_lock
BEFORE INSERT OR UPDATE ON mine_person_vehicle_binding
FOR EACH ROW
EXECUTE FUNCTION mine_lock_person_timeline();

CREATE FUNCTION mine_assert_person_binding_has_site_assignment()
RETURNS TRIGGER
LANGUAGE PLPGSQL
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM mine_person_site_assignment assignment
        WHERE assignment.organization_id = NEW.organization_id
          AND assignment.site_id = NEW.site_id
          AND assignment.person_id = NEW.person_id
          AND assignment.valid_period @> NEW.valid_period
    ) THEN
        RAISE EXCEPTION '人员绑定区间必须完整落在同矿区任职区间内'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ctg_mine_person_tag_site_assignment
AFTER INSERT OR UPDATE ON mine_person_tag_binding
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION mine_assert_person_binding_has_site_assignment();

CREATE CONSTRAINT TRIGGER ctg_mine_person_vehicle_site_assignment
AFTER INSERT OR UPDATE ON mine_person_vehicle_binding
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION mine_assert_person_binding_has_site_assignment();

CREATE FUNCTION mine_assert_site_assignment_covers_person_bindings()
RETURNS TRIGGER
LANGUAGE PLPGSQL
AS $$
DECLARE
    target_person UUID := OLD.person_id;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM mine_person_tag_binding binding
        WHERE binding.person_id = target_person
          AND NOT EXISTS (
              SELECT 1
              FROM mine_person_site_assignment assignment
              WHERE assignment.organization_id = binding.organization_id
                AND assignment.site_id = binding.site_id
                AND assignment.person_id = binding.person_id
                AND assignment.valid_period @> binding.valid_period
          )
    ) OR EXISTS (
        SELECT 1
        FROM mine_person_vehicle_binding binding
        WHERE binding.person_id = target_person
          AND NOT EXISTS (
              SELECT 1
              FROM mine_person_site_assignment assignment
              WHERE assignment.organization_id = binding.organization_id
                AND assignment.site_id = binding.site_id
                AND assignment.person_id = binding.person_id
                AND assignment.valid_period @> binding.valid_period
          )
    ) THEN
        RAISE EXCEPTION '人员矿区任职调整不能使已有绑定失去时间覆盖'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ctg_mine_person_site_assignment_binding_coverage
AFTER UPDATE OR DELETE ON mine_person_site_assignment
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION mine_assert_site_assignment_covers_person_bindings();

COMMENT ON TABLE mine_person IS '组织级人员主档及加密敏感身份信息';
COMMENT ON TABLE mine_driver_qualification IS '组织级司机可驾驶车型及有效期限资质';

COMMENT ON TABLE mine_sensitive_data_key_guard IS '人员敏感数据密钥身份与旧版密文升级完成门禁';
COMMENT ON COLUMN mine_sensitive_data_key_guard.purpose IS '密钥用途固定标识';
COMMENT ON COLUMN mine_sensitive_data_key_guard.verification_ciphertext IS '用于验证密钥身份的二版认证密文';
COMMENT ON COLUMN mine_sensitive_data_key_guard.legacy_upgrade_completed IS '旧版密文是否已全部升级完成';
COMMENT ON COLUMN mine_sensitive_data_key_guard.completed_at IS '旧版密文升级完成时间';
COMMENT ON COLUMN mine_sensitive_data_key_guard.created_at IS '门禁记录创建时间';
COMMENT ON COLUMN mine_sensitive_data_key_guard.updated_at IS '门禁记录最近更新时间';

COMMENT ON TABLE mine_person_site_assignment IS '人员在矿区的带时间区间任职关系';
COMMENT ON COLUMN mine_person_site_assignment.id IS '人员矿区任职关系唯一标识';
COMMENT ON COLUMN mine_person_site_assignment.organization_id IS '所属组织唯一标识';
COMMENT ON COLUMN mine_person_site_assignment.site_id IS '任职矿区唯一标识';
COMMENT ON COLUMN mine_person_site_assignment.person_id IS '组织级人员唯一标识';
COMMENT ON COLUMN mine_person_site_assignment.valid_from IS '矿区任职生效时间，包含该时刻';
COMMENT ON COLUMN mine_person_site_assignment.valid_to IS '矿区任职结束时间，不包含该时刻，为空表示持续有效';
COMMENT ON COLUMN mine_person_site_assignment.valid_period IS '由生效和结束时间生成的左闭右开区间';
COMMENT ON COLUMN mine_person_site_assignment.reason IS '新增或调整矿区任职的原因';
COMMENT ON COLUMN mine_person_site_assignment.created_at IS '任职关系创建时间';
COMMENT ON COLUMN mine_person_site_assignment.created_by IS '任职关系创建用户标识';
COMMENT ON COLUMN mine_person_site_assignment.updated_at IS '任职关系最近更新时间';
COMMENT ON COLUMN mine_person_site_assignment.updated_by IS '任职关系最近更新用户标识';
COMMENT ON COLUMN mine_person_site_assignment.version IS '任职关系乐观锁版本号';
