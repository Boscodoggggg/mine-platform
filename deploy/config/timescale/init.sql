CREATE EXTENSION IF NOT EXISTS timescaledb;

DO $$
BEGIN
    IF current_setting('timescaledb.license', true) IS DISTINCT FROM 'apache' THEN
        RAISE EXCEPTION 'mine-platform 仅允许使用 TimescaleDB Apache-2.0 OSS 模式';
    END IF;
END
$$;
