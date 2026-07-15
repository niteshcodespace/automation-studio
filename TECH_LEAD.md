# Automation Studio Engineering Handbook

## 1. Purpose

This document defines the engineering standards and delivery practices for Automation Studio.

It is the shared reference for:

- Architecture
- Development workflow
- AI-assisted engineering
- Branching
- Commit conventions
- Code review
- Testing
- Documentation
- Security
- Definition of Done

Automation Studio is developed as a production-quality, AI-native test orchestration platform rather than a demonstration or tutorial project.

---

## 2. Product Engineering Principles

### 2.1 Architecture Before Implementation

Implementation must begin only after the problem, requirements, boundaries, and design trade-offs are understood.

Before introducing a major feature, the team should identify:

- Business purpose
- Domain ownership
- API impact
- Database impact
- Security impact
- Operational impact
- Testing strategy
- Future extension points

### 2.2 Prefer Simple, Evolvable Designs

Use the simplest design that supports current requirements without preventing expected future growth.

Avoid:

- Premature microservices
- Empty abstractions
- Unnecessary framework layers
- Speculative features
- Over-generalized interfaces

Introduce complexity only when a concrete requirement justifies it.

### 2.3 AI Assists, Engineers Own

AI tools may generate code, tests, documentation, migrations, and implementation plans.

The engineer remains responsible for:

- Architecture
- Correctness
- Security
- Performance
- Maintainability
- Testing
- Final approval

AI-generated changes must never be committed without review.

### 2.4 Evidence Over Assumption

Engineering decisions should be supported by:

- Requirements
- Test results
- Logs
- Metrics
- Documentation
- Reproducible behavior

AI analysis must be grounded in execution evidence such as logs, screenshots, traces, reports, and structured results.

### 2.5 Secure by Default

The platform must avoid storing raw secrets in application tables or source control.

Use:

- Secret references
- Least-privilege access
- Input validation
- Safe logging
- Explicit authorization boundaries

### 2.6 Production Quality Per Commit

Every commit should leave the repository in a buildable, reviewable, and testable state.

---

## 3. Architecture Standards

### 3.1 Current Platform Direction

Automation Studio uses:

- Spring Boot modular monolith for the control plane
- Next.js for the frontend
- PostgreSQL for metadata and durable coordination
- Dedicated Java runner for execution
- Versioned automation-engine plugins
- Local filesystem artifact storage initially
- Extensible artifact storage abstraction for future object storage
- AI provider gateway for advisory capabilities
- OIDC-based authentication in a later milestone

### 3.2 Backend Layering

The backend currently uses these primary concerns:

```text
Controller
    ↓
DTO
    ↓
Service
    ↓
Mapper
    ↓
Repository
    ↓
Entity
    ↓
PostgreSQL
```

Supporting concerns include:

```text
Configuration
Exception handling
Validation
Testing
Logging
Security
```

### 3.3 Layer Responsibilities

#### Controller

Controllers:

- Receive HTTP requests
- Validate request DTOs
- Call application services
- Return HTTP responses

Controllers must not contain database logic or complex business rules.

#### Service

Services:

- Implement business rules
- Coordinate repositories
- Validate cross-entity conditions
- Control transaction boundaries
- Manage lifecycle transitions

#### Repository

Repositories:

- Perform persistence operations
- Define database queries
- Support pagination and sorting
- Use entity graphs or explicit queries when related data is required

#### Entity

Entities:

- Map to database tables
- Define persistence relationships
- Represent stored state

Entities must not be returned directly from public APIs.

#### DTO

DTOs:

- Define API contracts
- Apply request validation
- Prevent persistence details from leaking into APIs

#### Mapper

Mappers:

- Convert entities to DTOs
- Convert request models where appropriate
- Keep transformation logic out of controllers

#### Exception Handling

Global exception handling must produce consistent API error responses.

### 3.4 Dependency Direction

Dependencies should point inward toward application and domain behavior.

Avoid circular dependencies between packages.

A lower-level persistence component must not depend on a controller.

### 3.5 Entity Relationship Strategy

Use lazy loading for parent references:

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
```

Avoid adding bidirectional collections unless a concrete use case requires them.

Use:

- `@EntityGraph`
- Explicit JPQL
- Projection queries

when related data must be loaded.

Do not use `FetchType.EAGER` as a general solution to query problems.

### 3.6 Time Handling

Application time must come from an injected `Clock`.

Avoid direct use of:

```java
OffsetDateTime.now()
Instant.now()
LocalDateTime.now()
```

inside business logic when testability matters.

Store timestamps in UTC-compatible database types such as `TIMESTAMPTZ`.

### 3.7 IDs

Use UUID primary keys for core domain entities.

UUIDs support:

- Distributed processes
- Runner-generated references
- Safer public identifiers
- Future service extraction

### 3.8 Optimistic Locking

Use optimistic locking for entities whose lifecycle may be updated concurrently.

Example:

```java
@Version
private long version;
```

Execution lifecycle entities should protect against lost updates.

---

## 4. Domain and Database Standards

### 4.1 Source of Truth

Database design must be documented before implementation.

Primary reference:

```text
docs/database/domain-model.md
```

### 4.2 Migration Ownership

Liquibase is the authoritative mechanism for database schema changes.

Do not rely on Hibernate automatic schema creation in shared or production environments.

### 4.3 Migration Rules

Each migration must:

- Have a unique changeset ID
- Have a clear author
- Be deterministic
- Be reviewable
- Include rollback where practical
- Avoid destructive operations without explicit approval

Prefer one focused concern per migration file.

### 4.4 Naming

Use singular table names:

```text
workspace
project
environment
test_suite
execution
execution_job
execution_step
execution_artifact
```

Use snake_case for database columns and indexes.

Examples:

```text
project_id
requested_at
ix_execution_status
ux_project_workspace_name
```

### 4.5 Constraints

Validation must exist at the appropriate levels:

```text
API validation
Service-level business validation
Database constraints
```

Database constraints should include:

- Primary keys
- Foreign keys
- Unique constraints
- Not-null constraints
- Check constraints

### 4.6 Delete Strategy

Prefer archive or inactive states for long-lived business entities.

Avoid uncontrolled cascade deletion.

Retention-based deletion must be explicit, auditable, and separately designed.

---

## 5. API Standards

### 5.1 Resource-Oriented APIs

APIs should model resources and business operations clearly.

Examples:

```text
POST /api/executions
GET /api/executions/{id}
GET /api/executions
POST /api/executions/{id}/cancel
```

### 5.2 Do Not Expose Entities

Public endpoints must return response DTOs.

### 5.3 Validation

Request DTOs should use Jakarta Validation annotations such as:

```text
@NotNull
@NotBlank
@Size
@Positive
@PositiveOrZero
@Pattern
```

Cross-resource validation belongs in the service layer.

### 5.4 Error Responses

Errors should use a consistent structure containing relevant fields such as:

```json
{
  "timestamp": "2026-07-15T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Environment does not belong to project",
  "path": "/api/executions"
}
```

Do not expose internal stack traces through APIs.

### 5.5 Pagination

List endpoints expected to grow must support pagination.

Avoid returning unbounded execution history.

---

## 6. AI-Assisted Development Workflow

Automation Studio uses ChatGPT and Codex as engineering accelerators.

### 6.1 Design Phase

Before implementation:

1. Clarify the requirement.
2. Identify domain and architecture impact.
3. Define acceptance criteria.
4. Document important decisions.
5. Create an implementation plan.

### 6.2 Codex Implementation Phase

Codex may be asked to:

- Create files
- Generate boilerplate
- Implement approved designs
- Write tests
- Create Liquibase changesets
- Update documentation
- Refactor repetitive code

Codex prompts must include:

- Repository context
- Current branch
- Exact task
- Allowed files
- Forbidden changes
- Architecture constraints
- Test expectations
- Git restrictions

### 6.3 Review Phase

After Codex completes work:

1. Review `git status`.
2. Review the full diff.
3. Inspect generated files.
4. Verify architecture compliance.
5. Run tests.
6. Correct issues before staging.
7. Commit only approved changes.

### 6.4 AI Restrictions

AI tools must not:

- Commit without explicit instruction
- Push without explicit instruction
- Change unrelated files
- Introduce dependencies without review
- Store secrets
- Disable tests to make a build pass
- Replace architecture decisions with unreviewed alternatives

---

## 7. Branching Strategy

### 7.1 Main Branch

`main` must remain stable and buildable.

### 7.2 Feature Branches

Use one branch per issue.

Format:

```text
feature/AS-<issue-number>-<short-description>
```

Examples:

```text
feature/AS-009-execution-rest-api
feature/AS-010-domain-model
feature/AS-011-liquibase-schema
```

### 7.3 Other Branch Types

Examples:

```text
fix/AS-021-execution-timeout
docs/AS-022-runner-design
refactor/AS-023-base-entity
chore/AS-024-ci-improvements
```

### 7.4 Branch Rules

Before creating a new branch:

```text
Checkout main
Pull latest changes
Create branch
Confirm active branch
```

Do not mix unrelated issues in one branch.

---

## 8. Commit Conventions

Use Conventional Commits.

Format:

```text
<type>(<scope>): <description> (<issue>)
```

Examples:

```text
feat(execution): implement execution REST API (AS-009)
docs(database): define initial domain model (AS-010)
feat(database): add Liquibase baseline schema (AS-011)
test(execution): add execution service tests (AS-009)
refactor(persistence): introduce shared base entity (AS-012)
```

Supported types include:

```text
feat
fix
docs
test
refactor
chore
build
ci
perf
style
```

Commit messages should:

- Use imperative language
- Be specific
- Describe one logical change
- Include the issue number when applicable

Avoid messages such as:

```text
changes
update code
fix issue
work done
final changes
```

---

## 9. Code Review Checklist

Every code review should consider the following.

### 9.1 Correctness

- Does the implementation meet the acceptance criteria?
- Are edge cases handled?
- Are invalid states prevented?
- Are lifecycle transitions correct?

### 9.2 Architecture

- Does the change respect package boundaries?
- Is business logic in the service layer?
- Are entities hidden behind DTOs?
- Are abstractions justified?

### 9.3 Database

- Are constraints present?
- Are indexes appropriate?
- Are migrations safe?
- Are relationships lazy?
- Could the query introduce N+1 behavior?

### 9.4 API

- Is validation complete?
- Are response codes correct?
- Are errors consistent?
- Is pagination used where required?

### 9.5 Security

- Are secrets excluded?
- Is sensitive data logged?
- Can one workspace access another workspace's data?
- Is input safely validated?
- Are authorization checks required?

### 9.6 Performance

- Are queries bounded?
- Are unnecessary relationships fetched?
- Are large artifacts kept outside relational storage?
- Are indexes aligned with expected queries?

### 9.7 Maintainability

- Is the code readable?
- Are names clear?
- Is duplication justified?
- Are comments used only when they add value?
- Are tests understandable?

### 9.8 Documentation

- Does the change require an ADR?
- Does the domain model need updating?
- Does the API documentation need updating?
- Are operational steps documented?

---

## 10. Testing Strategy

### 10.1 Test Pyramid

Automation Studio should use:

```text
Unit tests
Controller tests
Repository tests
Integration tests
End-to-end tests
```

### 10.2 Unit Tests

Unit tests should validate:

- Business rules
- Status transitions
- Validation decisions
- Mapping behavior
- Error conditions

### 10.3 Controller Tests

Controller tests should validate:

- HTTP status codes
- Request validation
- Response payloads
- Error responses
- Pagination behavior

### 10.4 Repository Tests

Repository tests should validate:

- Custom queries
- Entity graphs
- Constraints
- Sorting
- Pagination
- Database-specific behavior

### 10.5 Integration Tests

Integration tests should validate:

- Spring context
- Liquibase migrations
- PostgreSQL interaction
- Transactions
- Complete request flows

### 10.6 Test Requirements

Before committing backend changes, run:

```powershell
mvn clean test
```

A commit must not proceed unless the output contains:

```text
BUILD SUCCESS
```

Tests must not depend on:

- Current wall-clock time
- Execution order
- External production services
- Developer-specific paths
- Shared mutable state

---

## 11. Documentation Standards

### 11.1 Documentation Structure

Recommended documentation structure:

```text
docs/
├── architecture/
├── database/
├── api/
├── adr/
├── diagrams/
├── runbooks/
├── decisions/
└── roadmap/
```

### 11.2 Architecture Decision Records

Create an ADR when a decision:

- Has long-term impact
- Introduces a major dependency
- Changes deployment topology
- Changes persistence strategy
- Changes security boundaries
- Introduces an important architectural pattern

An ADR should include:

```text
Context
Decision
Alternatives considered
Consequences
Status
```

### 11.3 Documentation Updates

Documentation is part of the feature.

A feature is incomplete if it changes architecture, API behavior, database behavior, or operational procedures without updating the relevant documentation.

---

## 12. Logging and Observability

Logs should be:

- Structured
- Searchable
- Safe
- Correlated

Relevant identifiers may include:

```text
request_id
correlation_id
execution_id
job_id
runner_id
workspace_id
project_id
```

Never log:

- Passwords
- Client secrets
- Access tokens
- Refresh tokens
- Private keys
- Raw secret values

Execution failures should remain traceable from API request through runner processing and artifact generation.

---

## 13. Security Standards

### 13.1 Secret Management

Secrets must be stored outside normal application tables wherever possible.

Store references such as:

```text
vault://workspace/project/environment/credential
```

rather than raw values.

### 13.2 Multi-Tenant Isolation

Workspace is the primary tenant boundary.

Future repository and service queries must ensure resources are accessed within the correct workspace.

### 13.3 Least Privilege

Runners, services, and users should receive only the permissions needed for their role.

### 13.4 Input Handling

Do not trust:

- URLs
- File names
- Repository paths
- Artifact paths
- User-supplied engine references
- Shell arguments

Validate and normalize inputs before use.

---

## 14. Definition of Ready

An issue is ready for implementation when:

- The business objective is clear
- Acceptance criteria are defined
- Dependencies are known
- Architecture impact is understood
- Database impact is identified
- Security considerations are identified
- Testing approach is defined
- Required documentation is identified

---

## 15. Definition of Done

An issue is complete only when:

- Acceptance criteria are satisfied
- Code follows the approved architecture
- Tests are added or updated
- `mvn clean test` succeeds for backend changes
- No unrelated files are modified
- The diff is reviewed
- Documentation is updated
- Security considerations are addressed
- Commit message follows conventions
- Branch is pushed
- Pull request is ready for review

Additional requirements may apply for specific features.

---

## 16. Architecture Review Process

Architecture review should occur:

- Before major new modules
- Before new external dependencies
- Before major database changes
- Before introducing a new deployable service
- Before security-boundary changes
- Before adopting a new automation engine
- Before production deployment changes

The review should examine:

```text
Business fit
Simplicity
Scalability
Security
Reliability
Observability
Operational cost
Migration risk
Future flexibility
```

---

## 17. Engineering Milestones

### Milestone 1: Platform Foundation

- Architecture baseline
- Backend bootstrap
- Frontend bootstrap
- Domain model
- Database migrations
- Execution API

### Milestone 2: Execution Platform

- Job orchestration
- Runner service
- Job claiming
- Retry behavior
- Cancellation
- Execution lifecycle

### Milestone 3: Automation Engines

- Playwright
- Selenium
- REST Assured
- Karate

### Milestone 4: AI Advisory Platform

- Evidence ingestion
- Failure summaries
- Root-cause suggestions
- Flaky-test insights
- Prompt and provider gateway

### Milestone 5: SaaS Capabilities

- Authentication
- Workspace membership
- Authorization
- Teams
- Auditability

### Milestone 6: Production Readiness

- CI/CD
- Containerization
- Kubernetes deployment
- Metrics
- Distributed tracing
- Operational runbooks

---

## 18. Handbook Ownership

This document is a living engineering standard.

Update it when:

- Development workflow changes
- Architecture principles change
- Review requirements change
- Testing standards evolve
- Security requirements evolve
- New engineering practices are adopted

Changes to this handbook must be reviewed like source-code changes.
