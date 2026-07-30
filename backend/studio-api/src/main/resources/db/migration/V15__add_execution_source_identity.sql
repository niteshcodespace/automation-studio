-- AS-023B: Project source configuration and immutable Execution source snapshots.

ALTER TABLE project
    ADD COLUMN source_type VARCHAR(30),
    ADD COLUMN source_repository VARCHAR(1000),
    ADD COLUMN source_revision VARCHAR(40);

ALTER TABLE test_suite
    ADD COLUMN source_location VARCHAR(500);

ALTER TABLE execution
    ADD COLUMN source_snapshot JSONB;

ALTER TABLE project
    ADD CONSTRAINT chk_project_source_configuration
        CHECK (
            (source_type IS NULL
                AND source_repository IS NULL
                AND source_revision IS NULL)
            OR
            (source_type = 'GIT_HTTPS'
                AND source_repository IS NOT NULL
                AND source_revision ~ '^[0-9a-f]{40}$')
        );

ALTER TABLE test_suite
    ADD CONSTRAINT chk_test_suite_source_location
        CHECK (
            source_location IS NULL
            OR (
                length(source_location) BETWEEN 1 AND 500
                AND source_location = btrim(source_location)
                AND left(source_location, 1) <> '/'
                AND left(source_location, 1) <> chr(92)
                AND source_location !~ '^[A-Za-z]:'
                AND position(chr(92) IN source_location) = 0
                AND position('//' IN source_location) = 0
                AND source_location <> '.'
                AND source_location <> '..'
                AND source_location NOT LIKE './%'
                AND source_location NOT LIKE '../%'
                AND source_location NOT LIKE '%/./%'
                AND source_location NOT LIKE '%/../%'
                AND source_location NOT LIKE '%/.'
                AND source_location NOT LIKE '%/..'
            )
        );

ALTER TABLE execution
    ADD CONSTRAINT chk_execution_source_snapshot
        CHECK (
            source_snapshot IS NULL
            OR jsonb_typeof(source_snapshot) = 'object'
        );

CREATE OR REPLACE FUNCTION reject_execution_snapshot_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.environment_snapshot IS DISTINCT FROM OLD.environment_snapshot
        OR NEW.suite_snapshot IS DISTINCT FROM OLD.suite_snapshot
        OR NEW.request_snapshot IS DISTINCT FROM OLD.request_snapshot
        OR NEW.source_snapshot IS DISTINCT FROM OLD.source_snapshot THEN
        RAISE EXCEPTION 'Execution snapshots are immutable after creation'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_execution_snapshots_immutable ON execution;

CREATE TRIGGER trg_execution_snapshots_immutable
BEFORE UPDATE OF environment_snapshot, suite_snapshot, request_snapshot, source_snapshot
ON execution
FOR EACH ROW
EXECUTE FUNCTION reject_execution_snapshot_update();
