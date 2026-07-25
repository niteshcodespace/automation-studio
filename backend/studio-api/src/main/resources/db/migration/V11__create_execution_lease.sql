-- AS-019B: Persist current renewable runner ownership without creating a separate queue.

CREATE TABLE execution_lease (
    execution_id UUID PRIMARY KEY,
    runner_id VARCHAR(150) NOT NULL,
    claim_token UUID NOT NULL,
    lease_generation BIGINT NOT NULL,
    claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_execution_lease_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution (id)
        ON DELETE RESTRICT,

    CONSTRAINT uk_execution_lease_claim_token
        UNIQUE (claim_token),

    CONSTRAINT chk_execution_lease_runner_id
        CHECK (BTRIM(runner_id) <> ''),

    CONSTRAINT chk_execution_lease_generation
        CHECK (lease_generation > 0),

    CONSTRAINT chk_execution_lease_version
        CHECK (version >= 0),

    CONSTRAINT chk_execution_lease_heartbeat_order
        CHECK (last_heartbeat_at >= claimed_at),

    CONSTRAINT chk_execution_lease_expiry_order
        CHECK (lease_expires_at > last_heartbeat_at),

    CONSTRAINT chk_execution_lease_audit_order
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_execution_pending_queue
    ON execution (requested_at ASC, id ASC)
    WHERE status = 'PENDING';

CREATE INDEX idx_execution_lease_expiry
    ON execution_lease (lease_expires_at ASC, execution_id ASC);

CREATE INDEX idx_execution_lease_runner_expiry
    ON execution_lease (runner_id, lease_expires_at);
