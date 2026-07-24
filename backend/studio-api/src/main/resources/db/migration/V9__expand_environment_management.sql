-- AS-017B: Expand the existing Environment aggregate for managed runtime targets.

ALTER TABLE environment
    ADD COLUMN description VARCHAR(1000),
    ADD COLUMN type VARCHAR(30),
    ADD COLUMN configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN secret_references JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE environment
SET type = 'TEST';

ALTER TABLE environment
    ALTER COLUMN type SET NOT NULL;

ALTER TABLE environment
    DROP CONSTRAINT chk_environment_status;

ALTER TABLE environment
    ADD CONSTRAINT chk_environment_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    ADD CONSTRAINT chk_environment_type
        CHECK (
            type IN (
                'LOCAL',
                'DEV',
                'TEST',
                'QA',
                'STAGING',
                'UAT',
                'PRODUCTION'
            )
        ),
    ADD CONSTRAINT chk_environment_configuration
        CHECK (jsonb_typeof(configuration) = 'object'),
    ADD CONSTRAINT chk_environment_secret_references
        CHECK (jsonb_typeof(secret_references) = 'object'),
    ADD CONSTRAINT chk_environment_version
        CHECK (version >= 0),
    ADD CONSTRAINT chk_environment_default_active
        CHECK (is_default = FALSE OR status = 'ACTIVE');

CREATE UNIQUE INDEX uk_environment_project_default
    ON environment (project_id)
    WHERE is_default = TRUE;
