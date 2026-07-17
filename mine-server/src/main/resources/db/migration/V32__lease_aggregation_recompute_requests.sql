-- 为显式聚合重算增加可恢复租约；稳定执行标识在租约转移时保持不变。
ALTER TABLE dev_aggregation_recompute_request
    ADD COLUMN claimed_by VARCHAR(128),
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE dev_aggregation_recompute_request
    DROP CONSTRAINT ck_dev_aggregation_recompute_lifecycle;

ALTER TABLE dev_aggregation_recompute_request
    ADD CONSTRAINT ck_dev_aggregation_recompute_claimed_by
        CHECK (claimed_by IS NULL OR btrim(claimed_by) <> ''),
    ADD CONSTRAINT ck_dev_aggregation_recompute_attempt
        CHECK (attempt_count >= 0 AND updated_at >= created_at),
    ADD CONSTRAINT ck_dev_aggregation_recompute_lifecycle CHECK (
        (status = 'PENDING'
            AND completed_at IS NULL AND execution_id IS NULL AND last_error IS NULL
            AND claimed_by IS NULL AND claim_token IS NULL AND claimed_at IS NULL
            AND lease_until IS NULL AND attempt_count = 0)
        OR (status = 'RUNNING'
            AND completed_at IS NULL AND execution_id IS NOT NULL AND last_error IS NULL
            AND claimed_by IS NOT NULL AND claim_token IS NOT NULL AND claimed_at IS NOT NULL
            AND lease_until IS NOT NULL AND lease_until > claimed_at AND attempt_count > 0)
        OR (status = 'SUCCEEDED'
            AND completed_at IS NOT NULL AND execution_id IS NOT NULL AND last_error IS NULL
            AND claimed_by IS NOT NULL AND claim_token IS NOT NULL AND claimed_at IS NOT NULL
            AND lease_until IS NULL AND attempt_count > 0)
        OR (status = 'FAILED'
            AND completed_at IS NOT NULL AND execution_id IS NOT NULL
            AND last_error IS NOT NULL AND btrim(last_error) <> ''
            AND claimed_by IS NOT NULL AND claim_token IS NOT NULL AND claimed_at IS NOT NULL
            AND lease_until IS NULL AND attempt_count > 0)
    );

DROP INDEX idx_dev_aggregation_recompute_pending;

CREATE INDEX idx_dev_aggregation_recompute_claimable
    ON dev_aggregation_recompute_request (created_at, request_id, lease_until)
    WHERE status IN ('PENDING', 'RUNNING');

COMMENT ON COLUMN dev_aggregation_recompute_request.claimed_by IS '当前认领请求的 Telemetry 实例标识';
COMMENT ON COLUMN dev_aggregation_recompute_request.claim_token IS '当前租约的随机认领令牌';
COMMENT ON COLUMN dev_aggregation_recompute_request.claimed_at IS '当前租约认领时间';
COMMENT ON COLUMN dev_aggregation_recompute_request.lease_until IS '当前租约到期时间';
COMMENT ON COLUMN dev_aggregation_recompute_request.attempt_count IS '累计认领尝试次数';
COMMENT ON COLUMN dev_aggregation_recompute_request.updated_at IS '请求状态最近更新时间';
