-- AS-020B: Persist runner identity and liveness independently from execution leases.

CREATE TABLE runner (
    id UUID PRIMARY KEY,
    runner_key VARCHAR(150) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    agent_version VARCHAR(100) NOT NULL,
    hostname VARCHAR(255) NOT NULL,
    operating_system VARCHAR(100) NOT NULL,
    architecture VARCHAR(50) NOT NULL,
    max_concurrency INTEGER NOT NULL,
    capabilities JSONB NOT NULL,
    labels JSONB NOT NULL,
    status VARCHAR(30) NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_runner_runner_key
        UNIQUE (runner_key),

    CONSTRAINT chk_runner_runner_key
        CHECK (runner_key ~ '^[a-z0-9][a-z0-9._-]{0,149}$'),

    CONSTRAINT chk_runner_name
        CHECK (BTRIM(name) <> ''),

    CONSTRAINT chk_runner_agent_version
        CHECK (BTRIM(agent_version) <> ''),

    CONSTRAINT chk_runner_hostname
        CHECK (BTRIM(hostname) <> ''),

    CONSTRAINT chk_runner_operating_system
        CHECK (BTRIM(operating_system) <> ''),

    CONSTRAINT chk_runner_architecture
        CHECK (BTRIM(architecture) <> ''),

    CONSTRAINT chk_runner_max_concurrency
        CHECK (max_concurrency BETWEEN 1 AND 1000),

    CONSTRAINT chk_runner_capabilities_object
        CHECK (jsonb_typeof(capabilities) = 'object'),

    CONSTRAINT chk_runner_capabilities_size
        CHECK (OCTET_LENGTH(capabilities::text) <= 65536),

    CONSTRAINT chk_runner_labels_object
        CHECK (jsonb_typeof(labels) = 'object'),

    CONSTRAINT chk_runner_labels_size
        CHECK (OCTET_LENGTH(labels::text) <= 65536),

    CONSTRAINT chk_runner_status
        CHECK (status IN ('ACTIVE', 'DISABLED', 'DEREGISTERED')),

    CONSTRAINT chk_runner_version
        CHECK (version >= 0),

    CONSTRAINT chk_runner_registration_order
        CHECK (last_registered_at >= registered_at),

    CONSTRAINT chk_runner_audit_order
        CHECK (updated_at >= created_at)
);

CREATE TABLE runner_runtime (
    runner_id UUID PRIMARY KEY,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_count BIGINT NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_runner_runtime_runner
        FOREIGN KEY (runner_id)
        REFERENCES runner (id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_runner_runtime_heartbeat_count
        CHECK (heartbeat_count >= 0),

    CONSTRAINT chk_runner_runtime_version
        CHECK (version >= 0),

    CONSTRAINT chk_runner_runtime_audit_order
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_runner_status_name
    ON runner (status, name, id);

CREATE INDEX idx_runner_capabilities_gin
    ON runner USING GIN (capabilities);

CREATE INDEX idx_runner_labels_gin
    ON runner USING GIN (labels);

CREATE INDEX idx_runner_runtime_last_seen
    ON runner_runtime (last_seen_at, runner_id);
