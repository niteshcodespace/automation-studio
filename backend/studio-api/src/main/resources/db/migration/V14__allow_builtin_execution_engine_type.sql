ALTER TABLE test_suite
    DROP CONSTRAINT chk_test_suite_engine_type;

ALTER TABLE test_suite
    ADD CONSTRAINT chk_test_suite_engine_type
        CHECK (
            engine_type IN (
                'PLAYWRIGHT',
                'SELENIUM',
                'KARATE',
                'REST_ASSURED',
                'PYTEST',
                'BUILTIN'
            )
        );
