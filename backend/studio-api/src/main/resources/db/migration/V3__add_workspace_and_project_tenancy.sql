-- Add workspace tenancy and scope project names by workspace.

CREATE TABLE workspace (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    description TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_workspace_slug UNIQUE (slug),
    CONSTRAINT chk_workspace_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED', 'ARCHIVED'))
);

INSERT INTO workspace (id, name, slug, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Default Workspace',
    'default',
    'ACTIVE'
);

ALTER TABLE project
    ADD COLUMN workspace_id UUID;

UPDATE project
SET workspace_id = '00000000-0000-0000-0000-000000000001';

ALTER TABLE project
    ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE project
    ADD CONSTRAINT fk_project_workspace
        FOREIGN KEY (workspace_id)
        REFERENCES workspace (id)
        ON DELETE RESTRICT;

ALTER TABLE project
    DROP CONSTRAINT uk_project_name;

ALTER TABLE project
    ADD CONSTRAINT uk_project_workspace_name
        UNIQUE (workspace_id, name);

CREATE INDEX idx_project_workspace_id
    ON project (workspace_id);

CREATE INDEX idx_workspace_status
    ON workspace (status);
