DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        CREATE EXTENSION timescaledb;
    END IF;
END
$$;

DO $$
BEGIN
    IF current_setting('timescaledb.license', true) IS DISTINCT FROM 'apache' THEN
        RAISE EXCEPTION 'mine-telemetry 仅允许使用 TimescaleDB Apache-2.0 OSS 模式';
    END IF;
END
$$;

CREATE TABLE telemetry_numeric (
    time            TIMESTAMPTZ      NOT NULL,
    device_id       UUID             NOT NULL,
    product_key     TEXT             NOT NULL CHECK (char_length(product_key) BETWEEN 1 AND 64),
    property_id     TEXT             NOT NULL CHECK (char_length(property_id) BETWEEN 1 AND 128),
    value_double    DOUBLE PRECISION,
    quality         SMALLINT         NOT NULL,
    boot_id         TEXT CHECK (boot_id IS NULL OR char_length(boot_id) BETWEEN 1 AND 64),
    sequence        BIGINT,
    event_id        UUID             NOT NULL,
    received_at     TIMESTAMPTZ      NOT NULL
);

SELECT create_hypertable('telemetry_numeric', by_range('time'));

CREATE UNIQUE INDEX uq_tn_event_property_time
    ON telemetry_numeric(event_id, property_id, time);
CREATE INDEX idx_tn_device_property_time
    ON telemetry_numeric(device_id, property_id, time DESC);
CREATE INDEX idx_tn_product_property_time
    ON telemetry_numeric(product_key, property_id, time DESC);

CREATE TABLE location_point (
    time                    TIMESTAMPTZ      NOT NULL,
    device_id               UUID,
    tag_id                  UUID             NOT NULL,
    x                       DOUBLE PRECISION NOT NULL,
    y                       DOUBLE PRECISION NOT NULL,
    z                       DOUBLE PRECISION,
    speed                   DOUBLE PRECISION,
    heading                 DOUBLE PRECISION,
    quality                 SMALLINT         NOT NULL,
    coordinate_system_id    UUID             NOT NULL,
    boot_id                 TEXT CHECK (boot_id IS NULL OR char_length(boot_id) BETWEEN 1 AND 64),
    sequence                BIGINT,
    event_id                UUID             NOT NULL,
    received_at             TIMESTAMPTZ      NOT NULL
);

SELECT create_hypertable('location_point', by_range('time'));

CREATE UNIQUE INDEX uq_location_event_time
    ON location_point(event_id, time);
CREATE INDEX idx_location_tag_time
    ON location_point(tag_id, time DESC);
CREATE INDEX idx_location_device_time
    ON location_point(device_id, time DESC)
    WHERE device_id IS NOT NULL;
