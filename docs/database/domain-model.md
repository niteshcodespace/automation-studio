# Automation Studio Domain Model

## 1. Purpose

This document defines the initial domain model and database structure for Automation Studio.

It is the source of truth for:

- JPA entity relationships
- PostgreSQL tables
- Liquibase migrations
- Database constraints
- Indexes
- Future platform extensions

The initial model supports projects, environments, test suites, executions, and execution artifacts while providing a foundation for multi-tenancy, runners, execution steps, security, and AI-assisted analysis.

---

## 2. Domain Overview

Automation Studio uses the following hierarchy:

```text
Workspace
    └── Project
          ├── Environment
          ├── Test Suite
          └── Execution
                ├── Execution Step
                └── Execution Artifact
```

A workspace represents the top-level tenant or organization.

A project represents one automation initiative or application.

An environment represents a target system where tests execute.

A test suite represents an executable collection of automated tests.

An execution represents one request to run a test suite.

An execution step represents an individual test or execution activity.

An execution artifact represents evidence generated during execution.

---

## 3. Entity Relationship Model

```text
+-------------------+
|     workspace     |
+-------------------+
| id PK             |
| name              |
| slug              |
| description       |
| status            |
| created_at        |
| updated_at        |
+---------+---------+
          |
          | 1
          |
          | N
+---------v---------+
|      project      |
+-------------------+
| id PK             |
| workspace_id FK   |
| name              |
| description       |
| status            |
| created_at        |
| updated_at        |
+---------+---------+
          |
          +-----------------------+
          |                       |
          | 1                     | 1
          |                       |
          | N                     | N
+---------v---------+   +---------v---------+
|    environment    |   |    test_suite     |
+-------------------+   +-------------------+
| id PK             |   | id PK             |
| project_id FK     |   | project_id FK     |
| name              |   | name              |
| base_url          |   | description       |
| status            |   | engine_type       |
| created_at        |   | suite_reference   |
| updated_at        |   | status            |
+-------------------+   | created_at        |
                        | updated_at        |
                        +---------+---------+
                                  |
                                  |
                        +---------v---------+
                        |     execution     |
                        +-------------------+
                        | id PK             |
                        | project_id FK     |
                        | environment_id FK |
                        | test_suite_id FK  |
                        | status            |
                        | requested_by      |
                        | requested_at      |
                        | started_at        |
                        | finished_at       |
                        | total_tests       |
                        | passed_tests      |
                        | failed_tests      |
                        | skipped_tests     |
                        | duration_ms       |
                        | error_message     |
                        | version           |
                        | created_at        |
                        | updated_at        |
                        +---------+---------+
                                  |
                     +------------+------------+
                     |                         |
                     | 1                       | 1
                     |                         |
                     | N                       | N
          +----------v----------+   +----------v-----------+
          |   execution_step    |   | execution_artifact   |
          +---------------------+   +-----------------------+
          | id PK               |   | id PK                 |
          | execution_id FK     |   | execution_id FK       |
          | name                |   | artifact_type         |
          | status              |   | file_name             |
          | sequence_number     |   | storage_location      |
          | started_at          |   | content_type          |
          | finished_at         |   | size_bytes            |
          | duration_ms         |   | created_at            |
          | error_message       |   +-----------------------+
          | created_at          |
          | updated_at          |
          +---------------------+
```

---

## 4. Table Specifications

### 4.1 workspace

Represents an organization, tenant, team, or individual account.

| Column      | Type         | Null | Description                          |
| ----------- | ------------ | ---: | ------------------------------------ |
| id          | UUID         |   No | Primary key                          |
| name        | VARCHAR(120) |   No | Workspace display name               |
| slug        | VARCHAR(120) |   No | URL-safe unique workspace identifier |
| description | TEXT         |  Yes | Workspace description                |
| status      | VARCHAR(30)  |   No | Workspace lifecycle status           |
| created_at  | TIMESTAMPTZ  |   No | Creation timestamp                   |
| updated_at  | TIMESTAMPTZ  |   No | Last update timestamp                |

Constraints:

```text
PK: workspace.id
UNIQUE: workspace.slug
```

Supported statuses:

```text
ACTIVE
INACTIVE
SUSPENDED
ARCHIVED
```

---

### 4.2 project

Represents an automation project inside a workspace.

| Column       | Type         | Null | Description              |
| ------------ | ------------ | ---: | ------------------------ |
| id           | UUID         |   No | Primary key              |
| workspace_id | UUID         |   No | Owning workspace         |
| name         | VARCHAR(120) |   No | Project name             |
| description  | TEXT         |  Yes | Project description      |
| status       | VARCHAR(30)  |   No | Project lifecycle status |
| created_at   | TIMESTAMPTZ  |   No | Creation timestamp       |
| updated_at   | TIMESTAMPTZ  |   No | Last update timestamp    |

Constraints:

```text
PK: project.id
FK: project.workspace_id → workspace.id
UNIQUE: project(workspace_id, name)
```

A project name must be unique only within its workspace.

Supported statuses:

```text
ACTIVE
INACTIVE
ARCHIVED
```

---

### 4.3 environment

Represents a target environment where automation executes.

| Column     | Type         | Null | Description                  |
| ---------- | ------------ | ---: | ---------------------------- |
| id         | UUID         |   No | Primary key                  |
| project_id | UUID         |   No | Owning project               |
| name       | VARCHAR(100) |   No | Environment name             |
| base_url   | VARCHAR(500) |   No | Target application base URL  |
| status     | VARCHAR(30)  |   No | Environment lifecycle status |
| created_at | TIMESTAMPTZ  |   No | Creation timestamp           |
| updated_at | TIMESTAMPTZ  |   No | Last update timestamp        |

Constraints:

```text
PK: environment.id
FK: environment.project_id → project.id
UNIQUE: environment(project_id, name)
```

Supported statuses:

```text
ACTIVE
INACTIVE
```

Secrets and credentials must not be stored directly in this table. Future versions will store secret references only.

---

### 4.4 test_suite

Represents an executable test collection.

| Column          | Type         | Null | Description                                                 |
| --------------- | ------------ | ---: | ----------------------------------------------------------- |
| id              | UUID         |   No | Primary key                                                 |
| project_id      | UUID         |   No | Owning project                                              |
| name            | VARCHAR(150) |   No | Test-suite name                                             |
| description     | TEXT         |  Yes | Test-suite description                                      |
| engine_type     | VARCHAR(50)  |   No | Automation engine                                           |
| suite_reference | VARCHAR(300) |   No | Repository path, package reference, or executable reference |
| status          | VARCHAR(30)  |   No | Test-suite lifecycle status                                 |
| created_at      | TIMESTAMPTZ  |   No | Creation timestamp                                          |
| updated_at      | TIMESTAMPTZ  |   No | Last update timestamp                                       |

Constraints:

```text
PK: test_suite.id
FK: test_suite.project_id → project.id
UNIQUE: test_suite(project_id, name)
```

Initial engine types:

```text
PLAYWRIGHT
SELENIUM
KARATE
REST_ASSURED
PYTEST
```

Initial statuses:

```text
ACTIVE
INACTIVE
ARCHIVED
```

---

### 4.5 execution

Represents one request to execute a test suite.

| Column         | Type         | Null | Description                         |
| -------------- | ------------ | ---: | ----------------------------------- |
| id             | UUID         |   No | Primary key                         |
| project_id     | UUID         |   No | Related project                     |
| environment_id | UUID         |   No | Target environment                  |
| test_suite_id  | UUID         |   No | Test suite being executed           |
| status         | VARCHAR(30)  |   No | Current execution status            |
| requested_by   | VARCHAR(150) |   No | User or system requesting execution |
| requested_at   | TIMESTAMPTZ  |   No | Time execution was requested        |
| started_at     | TIMESTAMPTZ  |  Yes | Time runner started execution       |
| finished_at    | TIMESTAMPTZ  |  Yes | Time execution ended                |
| total_tests    | INTEGER      |  Yes | Total number of tests               |
| passed_tests   | INTEGER      |  Yes | Number of passed tests              |
| failed_tests   | INTEGER      |  Yes | Number of failed tests              |
| skipped_tests  | INTEGER      |  Yes | Number of skipped tests             |
| duration_ms    | BIGINT       |  Yes | Total execution duration            |
| error_message  | TEXT         |  Yes | Execution-level failure message     |
| version        | BIGINT       |   No | Optimistic-lock version             |
| created_at     | TIMESTAMPTZ  |   No | Creation timestamp                  |
| updated_at     | TIMESTAMPTZ  |   No | Last update timestamp               |

Constraints:

```text
PK: execution.id
FK: execution.project_id → project.id
FK: execution.environment_id → environment.id
FK: execution.test_suite_id → test_suite.id
CHECK: total_tests >= 0
CHECK: passed_tests >= 0
CHECK: failed_tests >= 0
CHECK: skipped_tests >= 0
CHECK: duration_ms >= 0
```

Initial execution statuses:

```text
PENDING
CLAIMED
RUNNING
PASSED
FAILED
CANCELLED
ERROR
```

Business rules:

1. The environment must belong to the selected project.
2. The test suite must belong to the selected project.
3. A terminal execution status must not transition back to a running status.
4. `finished_at` must not be earlier than `started_at`.
5. Result counters must never be negative.
6. The total count should equal passed, failed, and skipped counts when final results are available.

Terminal statuses:

```text
PASSED
FAILED
CANCELLED
ERROR
```

---

### 4.6 execution_step

Represents an individual test case or execution activity.

| Column          | Type         | Null | Description               |
| --------------- | ------------ | ---: | ------------------------- |
| id              | UUID         |   No | Primary key               |
| execution_id    | UUID         |   No | Parent execution          |
| name            | VARCHAR(250) |   No | Step or test name         |
| status          | VARCHAR(30)  |   No | Step result status        |
| sequence_number | INTEGER      |   No | Ordering within execution |
| started_at      | TIMESTAMPTZ  |  Yes | Step start time           |
| finished_at     | TIMESTAMPTZ  |  Yes | Step finish time          |
| duration_ms     | BIGINT       |  Yes | Step duration             |
| error_message   | TEXT         |  Yes | Step failure details      |
| created_at      | TIMESTAMPTZ  |   No | Creation timestamp        |
| updated_at      | TIMESTAMPTZ  |   No | Last update timestamp     |

Constraints:

```text
PK: execution_step.id
FK: execution_step.execution_id → execution.id
UNIQUE: execution_step(execution_id, sequence_number)
CHECK: sequence_number >= 0
CHECK: duration_ms >= 0
```

Initial step statuses:

```text
PENDING
RUNNING
PASSED
FAILED
SKIPPED
ERROR
```

---

### 4.7 execution_artifact

Represents evidence generated by an execution.

| Column           | Type         | Null | Description                    |
| ---------------- | ------------ | ---: | ------------------------------ |
| id               | UUID         |   No | Primary key                    |
| execution_id     | UUID         |   No | Parent execution               |
| artifact_type    | VARCHAR(50)  |   No | Artifact category              |
| file_name        | VARCHAR(255) |   No | Original or generated filename |
| storage_location | TEXT         |   No | Storage key or location        |
| content_type     | VARCHAR(150) |  Yes | MIME content type              |
| size_bytes       | BIGINT       |  Yes | File size                      |
| created_at       | TIMESTAMPTZ  |   No | Creation timestamp             |

Constraints:

```text
PK: execution_artifact.id
FK: execution_artifact.execution_id → execution.id
CHECK: size_bytes >= 0
```

Initial artifact types:

```text
HTML_REPORT
SCREENSHOT
VIDEO
TRACE
LOG
JUNIT_XML
JSON_REPORT
OTHER
```

`storage_location` must contain a storage reference or object key, not unrestricted local user input.

---

## 5. Required Indexes

### Workspace indexes

```text
ux_workspace_slug
```

### Project indexes

```text
ix_project_workspace_id
ux_project_workspace_name
```

### Environment indexes

```text
ix_environment_project_id
ux_environment_project_name
```

### Test-suite indexes

```text
ix_test_suite_project_id
ux_test_suite_project_name
ix_test_suite_engine_type
```

### Execution indexes

```text
ix_execution_project_id
ix_execution_environment_id
ix_execution_test_suite_id
ix_execution_status
ix_execution_requested_at
ix_execution_project_requested_at
```

The composite execution index should support project execution-history queries:

```text
(project_id, requested_at DESC)
```

### Execution-step indexes

```text
ix_execution_step_execution_id
ux_execution_step_sequence
ix_execution_step_status
```

### Artifact indexes

```text
ix_execution_artifact_execution_id
ix_execution_artifact_type
```

---

## 6. Foreign-Key Delete Behaviour

The initial implementation should avoid automatic cascade deletion at the database level.

Recommended behaviour:

```text
Workspace with projects        → deletion blocked
Project with child records     → deletion blocked
Execution with steps/artifacts → deletion blocked
```

Business entities should normally be archived or made inactive instead of being physically deleted.

Later, a controlled retention process may delete old executions, steps, and artifacts.

---

## 7. JPA Relationship Strategy

All parent references should use lazy loading:

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
```

Collections should not be added to parent entities unless required by a use case.

For example, avoid adding this immediately:

```java
@OneToMany(mappedBy = "project")
private List<Execution> executions;
```

Repositories should fetch required relationships using:

```java
@EntityGraph
```

or explicit JPQL queries.

This avoids unnecessary data loading and reduces the risk of N+1 queries.

---

## 8. Validation Strategy

Validation occurs at multiple levels.

### API DTO validation

Examples:

```text
@NotBlank
@NotNull
@Size
@PositiveOrZero
```

### Service-layer validation

Examples:

```text
Environment belongs to project
Test suite belongs to project
Status transition is valid
Execution is not already terminal
```

### Database validation

Examples:

```text
NOT NULL
FOREIGN KEY
UNIQUE
CHECK
```

Validation must not rely only on Java annotations.

---

## 9. Audit Strategy

Initial audit fields:

```text
created_at
updated_at
requested_by
requested_at
```

Future authentication work may add:

```text
created_by
updated_by
```

The application should store timestamps in UTC using `TIMESTAMPTZ`.

Application time should come from an injected `Clock` so tests can use a fixed time source.

---

## 10. Future Entities

The following entities are intentionally deferred:

```text
workspace_member
user_account
engine_plugin
execution_job
runner
runner_heartbeat
secret_reference
schedule
execution_analysis
audit_event
notification
```

They are not required for the initial execution-management release.

The existing model must allow these features to be added without redesigning the core project and execution tables.

---

## 11. Initial Implementation Scope

AS-010 includes:

* Domain-model documentation
* Workspace design
* Execution-step design
* Enum definitions
* Database constraints
* Index definitions
* Relationship definitions

AS-010 does not include:

* Liquibase implementation
* User authentication
* Workspace membership
* Runner implementation
* Queue processing
* Artifact upload
* AI execution analysis
* Physical record deletion

Liquibase implementation will be completed in AS-011.

---

## 12. Design Decisions

### Decision 1: Use UUID primary keys

UUIDs provide globally unique identifiers and work well with distributed runner processes.

### Decision 2: Use singular table names

Examples:

```text
workspace
project
environment
test_suite
execution
execution_step
execution_artifact
```

### Decision 3: Keep associations lazy

Relationships are fetched only when required by a use case.

### Decision 4: Store enum values as strings

Enum values remain readable in the database and are safer than ordinal positions.

### Decision 5: Keep artifacts separate from executions

An execution may produce multiple files of different types and sizes.

### Decision 6: Introduce workspace before multi-user security

Workspace establishes the multi-tenant boundary without implementing authentication prematurely.

### Decision 7: Avoid storing secrets directly

Environment credentials and tokens will later be represented by secure external references.

---

## 13. Approval Status

```text
Status: DRAFT
Issue: AS-010
Next step: Review schema against existing JPA entities
Implementation issue: AS-011
```
