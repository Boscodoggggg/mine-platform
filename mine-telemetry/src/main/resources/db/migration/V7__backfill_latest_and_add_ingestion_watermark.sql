-- 摄取水位在事务级全局锁保护下分配，保证已提交数据的水位严格单调。
CREATE SEQUENCE telemetry_ingest_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;

COMMENT ON SEQUENCE telemetry_ingest_seq IS '遥测事件持久化摄取水位序列';

ALTER TABLE telemetry_inbox
    ADD COLUMN ingest_id BIGINT;

ALTER TABLE telemetry_numeric
    ADD COLUMN ingest_id BIGINT;

-- 先按历史消费完成顺序为已有 inbox 分配确定性水位。
WITH ordered_inbox AS (
    SELECT event_id,
           row_number() OVER (ORDER BY processed_at, event_id) AS ingest_id
    FROM telemetry_inbox
)
UPDATE telemetry_inbox inbox
SET ingest_id = ordered.ingest_id
FROM ordered_inbox ordered
WHERE inbox.event_id = ordered.event_id;

UPDATE telemetry_numeric numeric_data
SET ingest_id = inbox.ingest_id
FROM telemetry_inbox inbox
WHERE numeric_data.event_id = inbox.event_id;

-- 兼容 V3 上线前没有 inbox 记录的历史事件。
WITH current_max AS (
    SELECT COALESCE(MAX(ingest_id), 0) AS value FROM telemetry_inbox
), legacy_event AS (
    SELECT event_id,
           (SELECT value FROM current_max)
               + row_number() OVER (ORDER BY MIN(received_at), event_id) AS ingest_id
    FROM telemetry_numeric
    WHERE ingest_id IS NULL
    GROUP BY event_id
)
UPDATE telemetry_numeric numeric_data
SET ingest_id = legacy.ingest_id
FROM legacy_event legacy
WHERE numeric_data.event_id = legacy.event_id;

SELECT setval(
    'telemetry_ingest_seq',
    GREATEST(
        COALESCE((SELECT MAX(ingest_id) FROM telemetry_inbox), 0),
        COALESCE((SELECT MAX(ingest_id) FROM telemetry_numeric), 0),
        1
    ),
    EXISTS (SELECT 1 FROM telemetry_inbox) OR EXISTS (SELECT 1 FROM telemetry_numeric)
);

ALTER TABLE telemetry_inbox
    ALTER COLUMN ingest_id SET NOT NULL;

ALTER TABLE telemetry_numeric
    ALTER COLUMN ingest_id SET NOT NULL;

ALTER TABLE telemetry_inbox
    ADD CONSTRAINT uq_ti_ingest_id UNIQUE (ingest_id);

CREATE INDEX idx_tn_device_property_ingest_id
    ON telemetry_numeric(device_id, property_id, ingest_id);

COMMENT ON COLUMN telemetry_inbox.ingest_id IS '数据库提交顺序对应的全局摄取水位';
COMMENT ON COLUMN telemetry_numeric.ingest_id IS '所属事件的全局摄取水位';

CREATE TABLE telemetry_ingestion_watermark (
    singleton_id          SMALLINT    NOT NULL,
    committed_ingest_id   BIGINT      NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_telemetry_ingestion_watermark PRIMARY KEY (singleton_id),
    CONSTRAINT ck_tiw_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_tiw_committed_ingest CHECK (committed_ingest_id >= 0)
);

INSERT INTO telemetry_ingestion_watermark (singleton_id, committed_ingest_id)
SELECT 1,
       GREATEST(
           COALESCE((SELECT MAX(ingest_id) FROM telemetry_inbox), 0),
           COALESCE((SELECT MAX(ingest_id) FROM telemetry_numeric), 0)
       );

COMMENT ON TABLE telemetry_ingestion_watermark IS '已提交遥测事件的全局摄取水位';
COMMENT ON COLUMN telemetry_ingestion_watermark.singleton_id IS '单例记录固定标识，值恒为一';
COMMENT ON COLUMN telemetry_ingestion_watermark.committed_ingest_id IS '当前已提交的最大摄取水位';
COMMENT ON COLUMN telemetry_ingestion_watermark.updated_at IS '摄取水位最后更新时间（UTC）';

-- 按历史实际消费顺序重放 V6 之前的事件，恢复顺序权威状态、退役 boot 和属性最新值。
DO $migration$
DECLARE
    event_row RECORD;
    current_boot VARCHAR(64);
    current_sequence BIGINT;
    current_effective_at TIMESTAMPTZ;
    current_event_id UUID;
    reliable BOOLEAN;
    same_boot BOOLEAN;
    new_boot BOOLEAN;
    first_reliable_boot BOOLEAN;
    wins BOOLEAN;
BEGIN
    FOR event_row IN
        SELECT numeric_data.device_id,
               numeric_data.event_id,
               MIN(numeric_data.boot_id) AS boot_id,
               MIN(numeric_data.sequence) AS sequence,
               MIN(numeric_data.time) AS effective_at,
               MIN(numeric_data.ingest_id) AS ingest_id
        FROM telemetry_numeric numeric_data
        GROUP BY numeric_data.device_id, numeric_data.event_id
        ORDER BY MIN(numeric_data.ingest_id), numeric_data.event_id
    LOOP
        INSERT INTO telemetry_latest_state (device_id)
        VALUES (event_row.device_id)
        ON CONFLICT (device_id) DO NOTHING;

        SELECT state.current_boot_id,
               state.highest_sequence,
               state.latest_effective_at,
               state.latest_event_id
        INTO current_boot, current_sequence, current_effective_at, current_event_id
        FROM telemetry_latest_state state
        WHERE state.device_id = event_row.device_id;

        reliable := event_row.boot_id IS NOT NULL AND event_row.sequence IS NOT NULL;
        same_boot := reliable AND event_row.boot_id = current_boot;
        new_boot := reliable AND current_boot IS NOT NULL AND NOT same_boot;
        first_reliable_boot := reliable AND current_boot IS NULL;

        IF reliable AND EXISTS (
            SELECT 1
            FROM telemetry_retired_boot retired
            WHERE retired.device_id = event_row.device_id
              AND retired.boot_id = event_row.boot_id
        ) THEN
            CONTINUE;
        END IF;

        IF same_boot AND current_sequence IS NOT NULL
                AND event_row.sequence <= current_sequence THEN
            CONTINUE;
        END IF;

        wins := current_effective_at IS NULL
            OR event_row.effective_at > current_effective_at;
        IF NOT wins AND event_row.effective_at = current_effective_at THEN
            wins := (reliable AND (first_reliable_boot OR new_boot OR same_boot))
                OR (NOT reliable AND (
                    current_event_id IS NULL
                    OR event_row.event_id::TEXT > current_event_id::TEXT
                ));
        END IF;

        IF NOT wins THEN
            IF same_boot OR first_reliable_boot THEN
                UPDATE telemetry_latest_state
                SET current_boot_id = event_row.boot_id,
                    highest_sequence = event_row.sequence,
                    updated_at = CURRENT_TIMESTAMP
                WHERE device_id = event_row.device_id;
            END IF;
            CONTINUE;
        END IF;

        IF new_boot THEN
            INSERT INTO telemetry_retired_boot (device_id, boot_id)
            VALUES (event_row.device_id, current_boot)
            ON CONFLICT (device_id, boot_id) DO NOTHING;
        END IF;

        UPDATE telemetry_latest_state
        SET current_boot_id = CASE WHEN reliable THEN event_row.boot_id ELSE current_boot END,
            highest_sequence = CASE
                WHEN reliable THEN event_row.sequence ELSE current_sequence
            END,
            latest_effective_at = event_row.effective_at,
            latest_event_id = event_row.event_id,
            updated_at = CURRENT_TIMESTAMP
        WHERE device_id = event_row.device_id;

        INSERT INTO telemetry_latest_numeric (
            device_id, property_id, value_double, occurred_at, effective_at, received_at,
            quality, boot_id, sequence, event_id, updated_at
        )
        SELECT DISTINCT ON (numeric_data.device_id, numeric_data.property_id)
               numeric_data.device_id,
               numeric_data.property_id,
               numeric_data.value_double,
               numeric_data.occurred_at,
               numeric_data.time,
               numeric_data.received_at,
               numeric_data.quality,
               numeric_data.boot_id,
               numeric_data.sequence,
               numeric_data.event_id,
               CURRENT_TIMESTAMP
        FROM telemetry_numeric numeric_data
        WHERE numeric_data.event_id = event_row.event_id
          AND numeric_data.value_double IS NOT NULL
        ORDER BY numeric_data.device_id, numeric_data.property_id, numeric_data.time DESC
        ON CONFLICT (device_id, property_id) DO UPDATE SET
            value_double = EXCLUDED.value_double,
            occurred_at = EXCLUDED.occurred_at,
            effective_at = EXCLUDED.effective_at,
            received_at = EXCLUDED.received_at,
            quality = EXCLUDED.quality,
            boot_id = EXCLUDED.boot_id,
            sequence = EXCLUDED.sequence,
            event_id = EXCLUDED.event_id,
            updated_at = CURRENT_TIMESTAMP;
    END LOOP;
END
$migration$;
