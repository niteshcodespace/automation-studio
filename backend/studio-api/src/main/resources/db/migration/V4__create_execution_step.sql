-- Add ordered execution-step tracking and lifecycle validation.

CREATE TABLE execution_step (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL,
    name VARCHAR(250) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    sequence_number INTEGER NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_execution_step_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution (id)
        ON DELETE RESTRICT,

    CONSTRAINT uk_execution_step_sequence
        UNIQUE (execution_id, sequence_number),

    CONSTRAINT chk_execution_step_status
        CHECK (
            status IN (
                'PENDING',
                'RUNNING',
                'PASSED',
                'FAILED',
                'SKIPPED',
                'ERROR'
            )
        ),

    CONSTRAINT chk_execution_step_sequence_number
        CHECK (sequence_number >= 0),

    CONSTRAINT chk_execution_step_duration_ms
        CHECK (duration_ms IS NULL OR duration_ms >= 0),

    CONSTRAINT chk_execution_step_time_order
        CHECK (
            finished_at IS NULL
            OR started_at IS NULL
            OR finished_at >= started_at
        )
);

CREATE INDEX idx_execution_step_execution_id
    ON execution_step (execution_id);

CREATE INDEX idx_execution_step_status
    ON execution_step (status);
