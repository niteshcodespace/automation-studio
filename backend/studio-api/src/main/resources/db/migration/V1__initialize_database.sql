CREATE TABLE schema_version_marker (
    id BIGSERIAL PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_version_marker (description)
VALUES ('Automation Studio database initialized');