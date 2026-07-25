-- AS-018B: Extend the existing Execution aggregate for managed execution admission.

ALTER TABLE execution
    ADD COLUMN selection_mode VARCHAR(30) DEFAULT 'SUITE',
    ADD COLUMN environment_snapshot JSONB,
    ADD COLUMN suite_snapshot JSONB,
    ADD COLUMN request_snapshot JSONB,
    ADD COLUMN cancel_requested_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN cancelled_by VARCHAR(150),
    ADD COLUMN cancellation_reason VARCHAR(1000);

UPDATE execution
SET selection_mode = 'SUITE'
WHERE selection_mode IS NULL;

ALTER TABLE execution
    ALTER COLUMN selection_mode SET NOT NULL,
    ALTER COLUMN selection_mode DROP DEFAULT;

ALTER TABLE execution
    DROP CONSTRAINT chk_execution_status;

ALTER TABLE execution
    ADD CONSTRAINT chk_execution_status
        CHECK (
            status IN (
                'PENDING',
                'CLAIMED',
                'RUNNING',
                'CANCEL_REQUESTED',
                'PASSED',
                'FAILED',
                'CANCELLED',
                'ERROR'
            )
        ),
    ADD CONSTRAINT chk_execution_selection_mode
        CHECK (selection_mode IN ('SUITE', 'TEST_CASES')),
    ADD CONSTRAINT chk_execution_environment_snapshot
        CHECK (
            environment_snapshot IS NULL
            OR jsonb_typeof(environment_snapshot) = 'object'
        ),
    ADD CONSTRAINT chk_execution_suite_snapshot
        CHECK (
            suite_snapshot IS NULL
            OR jsonb_typeof(suite_snapshot) = 'object'
        ),
    ADD CONSTRAINT chk_execution_request_snapshot
        CHECK (
            request_snapshot IS NULL
            OR jsonb_typeof(request_snapshot) = 'object'
        ),
    ADD CONSTRAINT chk_execution_cancellation_time_order
        CHECK (
            cancelled_at IS NULL
            OR cancel_requested_at IS NULL
            OR cancelled_at >= cancel_requested_at
        );

CREATE TABLE execution_test_case (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL,
    automation_test_case_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    test_case_snapshot JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_execution_test_case_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_execution_test_case_automation_test_case
        FOREIGN KEY (automation_test_case_id)
        REFERENCES automation_test_case (id)
        ON DELETE RESTRICT,

    CONSTRAINT uk_execution_test_case_execution_case
        UNIQUE (execution_id, automation_test_case_id),

    CONSTRAINT uk_execution_test_case_execution_sequence
        UNIQUE (execution_id, sequence_number),

    CONSTRAINT chk_execution_test_case_sequence_number
        CHECK (sequence_number >= 0),

    CONSTRAINT chk_execution_test_case_snapshot
        CHECK (
            test_case_snapshot IS NULL
            OR jsonb_typeof(test_case_snapshot) = 'object'
        )
);

CREATE INDEX idx_execution_test_case_automation_test_case_id
    ON execution_test_case (automation_test_case_id);
