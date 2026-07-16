ALTER TABLE telemetry_latest_state
    ADD COLUMN first_seen_at TIMESTAMPTZ,
    ADD COLUMN last_received_at TIMESTAMPTZ;

WITH inbox_activity AS (
    SELECT device_id,
           MIN(received_at) AS first_seen_at,
           MAX(received_at) AS last_received_at
    FROM telemetry_inbox
    GROUP BY device_id
)
UPDATE telemetry_latest_state state
SET first_seen_at = activity.first_seen_at,
    last_received_at = activity.last_received_at
FROM inbox_activity activity
WHERE state.device_id = activity.device_id;

WITH history_activity AS (
    SELECT device_id,
           MIN(received_at) AS first_seen_at,
           MAX(received_at) AS last_received_at
    FROM telemetry_numeric
    GROUP BY device_id
)
UPDATE telemetry_latest_state state
SET first_seen_at = CASE
        WHEN state.first_seen_at IS NULL THEN activity.first_seen_at
        ELSE LEAST(state.first_seen_at, activity.first_seen_at)
    END,
    last_received_at = CASE
        WHEN state.last_received_at IS NULL THEN activity.last_received_at
        ELSE GREATEST(state.last_received_at, activity.last_received_at)
    END
FROM history_activity activity
WHERE state.device_id = activity.device_id;

COMMENT ON COLUMN telemetry_latest_state.first_seen_at IS '第一条通过平台校验并成功持久化的设备报文接收时间（UTC）';
COMMENT ON COLUMN telemetry_latest_state.last_received_at IS '最近一条通过平台校验并成功持久化的设备报文接收时间（UTC）';
