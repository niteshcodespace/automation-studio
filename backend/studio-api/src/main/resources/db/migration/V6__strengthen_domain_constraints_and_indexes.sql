-- Strengthen engine and artifact constraints and restrict artifact deletion.

ALTER TABLE test_suite
    ADD CONSTRAINT chk_test_suite_engine_type
        CHECK (
            engine_type IN (
                'PLAYWRIGHT',
                'SELENIUM',
                'KARATE',
                'REST_ASSURED',
                'PYTEST'
            )
        );

ALTER TABLE execution_artifact
    ADD CONSTRAINT chk_execution_artifact_type
        CHECK (
            artifact_type IN (
                'HTML_REPORT',
                'SCREENSHOT',
                'VIDEO',
                'TRACE',
                'LOG',
                'JUNIT_XML',
                'JSON_REPORT',
                'OTHER'
            )
        );

ALTER TABLE execution_artifact
    DROP CONSTRAINT fk_execution_artifact_execution;

ALTER TABLE execution_artifact
    ADD CONSTRAINT fk_execution_artifact_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution (id)
        ON DELETE RESTRICT;

CREATE INDEX idx_test_suite_engine_type
    ON test_suite (engine_type);

CREATE INDEX idx_execution_artifact_type
    ON execution_artifact (artifact_type);
