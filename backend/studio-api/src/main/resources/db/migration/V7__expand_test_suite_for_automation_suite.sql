-- AS-015A: Add transitional AutomationSuite fields to the existing suite aggregate.

ALTER TABLE test_suite
    ADD COLUMN engine_id VARCHAR(100),
    ADD COLUMN suite_type VARCHAR(30),
    ADD COLUMN configuration JSONB,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE test_suite
    ADD CONSTRAINT chk_test_suite_suite_type
        CHECK (
            suite_type IS NULL
            OR suite_type IN (
                'API',
                'UI',
                'MOBILE',
                'PERFORMANCE',
                'SECURITY',
                'DATABASE'
            )
        ),
    ADD CONSTRAINT chk_test_suite_configuration
        CHECK (
            configuration IS NULL
            OR jsonb_typeof(configuration) = 'object'
        ),
    ADD CONSTRAINT chk_test_suite_version
        CHECK (version >= 0);
