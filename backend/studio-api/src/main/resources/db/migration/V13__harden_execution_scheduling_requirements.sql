-- AS-021B: Harden immutable execution scheduling inputs and index compatible queue scans.

CREATE OR REPLACE FUNCTION reject_execution_snapshot_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.environment_snapshot IS DISTINCT FROM OLD.environment_snapshot
        OR NEW.suite_snapshot IS DISTINCT FROM OLD.suite_snapshot
        OR NEW.request_snapshot IS DISTINCT FROM OLD.request_snapshot THEN
        RAISE EXCEPTION 'Execution snapshots are immutable after creation'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_execution_snapshots_immutable ON execution;

CREATE TRIGGER trg_execution_snapshots_immutable
BEFORE UPDATE OF environment_snapshot, suite_snapshot, request_snapshot
ON execution
FOR EACH ROW
EXECUTE FUNCTION reject_execution_snapshot_update();

CREATE INDEX IF NOT EXISTS idx_execution_pending_engine_queue
    ON execution ((suite_snapshot ->> 'engineId'), requested_at ASC, id ASC)
    WHERE status = 'PENDING'
      AND NULLIF(BTRIM(suite_snapshot ->> 'engineId'), '') IS NOT NULL;
