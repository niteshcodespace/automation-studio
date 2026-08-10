-- Reconcile legacy artifact references with the canonical AS-028 metadata contract.

ALTER TABLE execution_artifact DROP CONSTRAINT chk_execution_artifact_type;

ALTER TABLE execution_artifact RENAME COLUMN artifact_type TO category;
ALTER TABLE execution_artifact RENAME COLUMN file_name TO logical_name;
ALTER TABLE execution_artifact RENAME COLUMN content_type TO media_type;
ALTER TABLE execution_artifact RENAME COLUMN storage_location TO legacy_storage_location;

UPDATE execution_artifact
SET category = CASE category
    WHEN 'HTML_REPORT' THEN 'REPORT'
    WHEN 'JUNIT_XML' THEN 'REPORT'
    WHEN 'JSON_REPORT' THEN 'REPORT'
    WHEN 'OTHER' THEN 'ATTACHMENT'
    ELSE category
END,
media_type = COALESCE(media_type, 'application/octet-stream'),
size_bytes = COALESCE(size_bytes, 0);

ALTER TABLE execution_artifact
    ALTER COLUMN category TYPE VARCHAR(128),
    ALTER COLUMN media_type TYPE VARCHAR(255),
    ALTER COLUMN media_type SET NOT NULL,
    ALTER COLUMN size_bytes SET NOT NULL,
    ALTER COLUMN legacy_storage_location DROP NOT NULL,
    ADD COLUMN storage_reference VARCHAR(64),
    ADD COLUMN checksum_algorithm VARCHAR(16),
    ADD COLUMN checksum VARCHAR(64),
    ADD COLUMN retention_reference VARCHAR(128),
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE execution_artifact
    ADD CONSTRAINT chk_execution_artifact_category
        CHECK (category ~ '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'),
    ADD CONSTRAINT chk_execution_artifact_logical_name
        CHECK (length(btrim(logical_name)) > 0 AND logical_name !~ '[[:cntrl:]]'),
    ADD CONSTRAINT chk_execution_artifact_media_type
        CHECK (length(btrim(media_type)) > 0 AND media_type !~ '[[:space:][:cntrl:]]'),
    ADD CONSTRAINT chk_execution_artifact_storage_reference
        CHECK (storage_reference IS NULL OR storage_reference ~
            '^artifact:v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    ADD CONSTRAINT chk_execution_artifact_integrity
        CHECK (storage_reference IS NULL OR (
            checksum_algorithm = 'SHA-256'
            AND checksum ~ '^[0-9a-f]{64}$'
            AND retention_reference IS NOT NULL
            AND length(btrim(retention_reference)) > 0
        )),
    ADD CONSTRAINT chk_execution_artifact_metadata
        CHECK (jsonb_typeof(metadata) = 'object' AND octet_length(metadata::text) <= 8192);

DROP INDEX idx_execution_artifact_type;
CREATE INDEX idx_execution_artifact_category ON execution_artifact (category);
CREATE INDEX idx_execution_artifact_execution_created
    ON execution_artifact (execution_id, created_at, id);
CREATE UNIQUE INDEX uk_execution_artifact_storage_reference
    ON execution_artifact (storage_reference) WHERE storage_reference IS NOT NULL;
