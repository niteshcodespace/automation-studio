-- AS-016B: Add independently persisted Automation Test Cases.

CREATE TABLE automation_test_case (
    id UUID PRIMARY KEY,
    test_suite_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    case_reference VARCHAR(300) NOT NULL,
    configuration JSONB,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    position INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_automation_test_case_test_suite
        FOREIGN KEY (test_suite_id)
        REFERENCES test_suite (id)
        ON DELETE RESTRICT,

    CONSTRAINT uk_automation_test_case_suite_name
        UNIQUE (test_suite_id, name),

    CONSTRAINT uk_automation_test_case_suite_reference
        UNIQUE (test_suite_id, case_reference),

    CONSTRAINT uk_automation_test_case_suite_position
        UNIQUE (test_suite_id, position)
        DEFERRABLE INITIALLY DEFERRED,

    CONSTRAINT chk_automation_test_case_configuration
        CHECK (
            configuration IS NULL
            OR jsonb_typeof(configuration) = 'object'
        ),

    CONSTRAINT chk_automation_test_case_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),

    CONSTRAINT chk_automation_test_case_position
        CHECK (position >= 0),

    CONSTRAINT chk_automation_test_case_version
        CHECK (version >= 0)
);

CREATE INDEX idx_automation_test_case_suite_status
    ON automation_test_case (test_suite_id, status);
