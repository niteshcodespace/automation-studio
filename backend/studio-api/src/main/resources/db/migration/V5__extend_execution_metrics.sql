-- Extend execution metrics and improve requested-at query performance.

ALTER TABLE execution
    ADD COLUMN skipped_tests INTEGER,
    ADD COLUMN duration_ms BIGINT;

ALTER TABLE execution
    ADD CONSTRAINT chk_execution_skipped_tests
        CHECK (skipped_tests IS NULL OR skipped_tests >= 0),
    ADD CONSTRAINT chk_execution_duration_ms
        CHECK (duration_ms IS NULL OR duration_ms >= 0);

ALTER TABLE execution
    DROP CONSTRAINT chk_execution_test_counts;

ALTER TABLE execution
    ADD CONSTRAINT chk_execution_test_counts
        CHECK (
            total_tests IS NULL
            OR (
                COALESCE(passed_tests, 0)
                + COALESCE(failed_tests, 0)
                + COALESCE(skipped_tests, 0)
                <= total_tests
            )
        );

CREATE INDEX idx_execution_project_requested_at
    ON execution (project_id, requested_at DESC);

CREATE INDEX idx_execution_requested_at
    ON execution (requested_at);
