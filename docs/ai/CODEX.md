# Automation Studio AI Engineering Playbook

This playbook is the repository-specific source of truth for AI-assisted and human engineering work in Automation Studio. It records the architecture that exists today, the direction documented for future releases, and the controls required to preserve both. When a story gives narrower instructions, follow the story within these guardrails. When sources disagree, use the precedence rules below and report the conflict instead of silently choosing a new design.

> **Rule:** Inspect before editing. Preserve established behavior, keep the change set small, and never treat a future-state architecture diagram as proof that a component already exists.

## Table of Contents

1. [Project Vision](#1-project-vision)
2. [Engineering Principles](#2-engineering-principles)
3. [Repository Structure](#3-repository-structure)
4. [High-Level Architecture Overview](#4-high-level-architecture-overview)
5. [Layer Responsibilities](#5-layer-responsibilities)
6. [Package Organization](#6-package-organization)
7. [Coding Standards Summary](#7-coding-standards-summary)
8. [Feature Implementation Workflow](#8-feature-implementation-workflow)
9. [AI Collaboration Workflow](#9-ai-collaboration-workflow)
10. [Repository Inspection Checklist](#10-repository-inspection-checklist)
11. [Scope Control Rules](#11-scope-control-rules)
12. [Allowed Changes](#12-allowed-changes)
13. [Forbidden Changes](#13-forbidden-changes)
14. [Testing Requirements](#14-testing-requirements)
15. [Documentation Requirements](#15-documentation-requirements)
16. [Git Workflow](#16-git-workflow)
17. [Pull Request Expectations](#17-pull-request-expectations)
18. [Definition of Done](#18-definition-of-done)
19. [Completion Report Template](#19-completion-report-template)
20. [Common Mistakes to Avoid](#20-common-mistakes-to-avoid)
21. [Future Expansion Guidelines](#21-future-expansion-guidelines)
22. [Appendix](#22-appendix)

## 1. Project Vision

Automation Studio is an AI-native, modular quality-engineering platform for managing automation projects, environments, suites, executions, evidence, and reports. The v0.1 outcome is deliberately narrow: run an OrangeHRM Playwright smoke suite and display its result.

“AI-native” describes explicit future boundaries for context, providers, provenance, safety, and approval. It does **not** make AI a dependency of the core execution path. AI advice is evidence-grounded, audited, and non-authoritative; generated tests or fixes remain proposals until approved.

The platform is intended to grow from a small self-hosted system into an enterprise-capable product without prematurely adopting distributed infrastructure. Its documented direction separates:

- a Spring Boot control plane;
- a dedicated Java execution runner;
- versioned automation-engine plugins, initially Playwright Java;
- a Next.js web client;
- PostgreSQL metadata and durable work/outbox facilities;
- external artifact storage behind a port;
- optional AI and MCP capabilities.

Only the Spring Boot persistence foundation and PostgreSQL development setup are materially present in this repository today. Engineers must distinguish implemented behavior from architectural intent.

## 2. Engineering Principles

1. **Maintainability over speed.** Optimize for code that the next engineer can safely understand and change.
2. **Architecture before implementation.** Identify the owning layer and boundary before adding code.
3. **Design before coding.** Resolve schema, API, ownership, state-transition, and compatibility questions first.
4. **Small, reviewable changes.** A story should produce the smallest coherent diff that satisfies its acceptance criteria.
5. **Explicit business rules.** Put business decisions in services and durable invariants in the database; do not hide them in controllers or naming tricks.
6. **Thin controllers.** HTTP code validates the boundary and delegates the use case.
7. **Service-oriented business logic.** Services coordinate transactions, authorization-ready scoping, invariants, and repository access.
8. **Separation of responsibilities.** DTOs are boundary contracts, entities are persistence models, mappers translate, and repositories persist.
9. **Database truth is explicit.** Flyway migrations define the deployed schema; JPA mappings and validation must agree with them.
10. **Automated verification.** Add tests proportional to risk and run the relevant build before declaring completion.
11. **Documentation is part of delivery.** Update authoritative guidance when a behavior or approved decision changes.
12. **Backward compatibility where practical.** Do not break public APIs, persisted enum values, migrations, or engine contracts casually.
13. **Consistency over preference.** Follow local patterns unless they are unsafe or demonstrably incorrect; escalate improvements separately.
14. **Security by default.** Never persist, log, expose, or commit secret values.

## 3. Repository Structure

The current repository is a scaffold for a broader platform. Empty or absent directories do not imply an implemented capability.

| Path | Current responsibility and status |
|---|---|
| `backend/studio-api/` | Active Spring Boot API module; currently entities, enums, repositories, Flyway migrations, and a context-load test. |
| `backend/studio-api/src/main/java/com/automationstudio/api/` | Java base package for the current API implementation. |
| `backend/studio-api/src/main/resources/db/migration/` | Immutable, ordered Flyway schema history. |
| `backend/studio-api/src/test/` | Backend tests; currently only the application context smoke test. |
| `docs/architecture/` | Target system, module, sequence, and deployment architecture; some files are placeholders. |
| `docs/adr/` | Architecture decisions; ADR-001 is substantive, ADR-002 through ADR-004 are headings only. |
| `docs/database/` | Detailed domain-model guidance; useful but contains stale terminology noted in the appendix. |
| `docs/engineering/` | Naming, branching, commit, review, coding, and testing guidance; several files are placeholders. |
| `docs/agile/` | Story and Definition of Done material. |
| `docs/roadmap/`, `docs/vision/` | Product direction, release scope, and mission. |
| `docs/development-log/`, `docs/releases/` | Historical implementation and release records. |
| `docs/ai/` | AI engineering guidance, including this playbook. |
| `docker-compose.yml` | Local PostgreSQL 16 development service. |
| `apps/`, `engines/`, `infrastructure/`, `demo-projects/` | Reserved top-level capability areas; no tracked implementation was found during this playbook review. |
| `scripts/` | Repository automation, currently including `bootstrap.ps1`. |

`README.md`, `AGENTS.md`, and `CONTRIBUTING.md` are currently empty. They are still reserved governance entry points and must not be populated incidentally during feature work.

## 4. High-Level Architecture Overview

### 4.1 Target architecture

The approved direction is a modular monolith for the control plane with separate execution runtime boundaries:

```text
User -> Next.js Web -> Spring Boot Studio API -> PostgreSQL
                              |
                              +-> durable work/outbox -> Java Runner -> Engine Plugin -> SUT
                              |
                              +-> artifact port -> local/S3-compatible storage
                              |
                              +-> optional governed AI and MCP adapters
```

The control plane owns authoritative business state. The runner claims work, prepares isolated workspaces, resolves scoped secrets, invokes engines, collects normalized results, and reports guarded state changes. Engines contain technology-specific execution behavior and must not write platform tables or decide authorization. AI and MCP use the same application services and approval rules as other adapters.

### 4.2 Current implementation

The implemented backend is a conventional layered Spring Boot application using Java 21, Spring Data JPA, Jakarta Validation, Lombok, PostgreSQL, and Flyway. It contains these persisted concepts:

```text
Workspace
  `-- Project
       |-- Environment
       |-- TestSuite
       `-- Execution
            |-- ExecutionStep
            `-- ExecutionArtifact
```

UUIDs are application-generated with `GenerationType.UUID`. Parent references are unidirectional lazy `ManyToOne` relationships. Enums are stored as strings. Audit timestamps use `OffsetDateTime`, `@CreationTimestamp`, and `@UpdateTimestamp`. `Execution` also uses optimistic locking through `@Version`.

The API currently has no implemented controllers, services, DTOs, mappers, security module, runner, engine contract, outbox, AI service, MCP server, or web client in tracked source. Package placeholders express intended layering, not completed features.

### 4.3 Source-of-truth precedence

Use this order when implementing a story:

1. The current story's explicit acceptance criteria and approved clarifications.
2. Applied Flyway migrations for database shape and persisted constraints.
3. Existing compiling implementation and tests for code conventions and behavior.
4. Accepted, substantive ADRs.
5. Architecture and domain documentation.
6. Roadmaps, plans, templates, placeholders, and examples.

> **Conflict rule:** Do not guess. Report the exact conflict, identify the affected files or contracts, and request a decision when satisfying both sources is impossible. Never edit an applied migration to make code fit.

## 5. Layer Responsibilities

### 5.1 Controller

Controllers are inbound web adapters. They should:

- define versioned HTTP routes and status codes;
- accept request DTOs and return response DTOs;
- apply Jakarta boundary validation with `@Valid`;
- extract identifiers and future authenticated actor context;
- delegate one use case to a service;
- avoid exposing JPA entities or lazy relationships.

Controllers must not contain transaction orchestration, repository calls, ownership checks, entity mutation rules, engine invocation, or exception-to-response duplication.

### 5.2 Service

Services implement application use cases. They should:

- own transaction boundaries;
- load required records through repositories;
- enforce cross-entity rules such as workspace/project ownership;
- enforce valid state transitions and terminal-state behavior;
- coordinate persistence and mapping;
- expose focused operations that can be reused by REST, MCP, or other adapters;
- use an injected `Clock` for new business-time decisions when practical.

Do not let one module bypass another module's invariants by directly changing its records. As business modules emerge, use published application interfaces or domain events.

### 5.3 Repository

Repositories are Spring Data persistence ports for entities. They should:

- extend `JpaRepository<Entity, UUID>` under current conventions;
- use derived query names for clear, simple predicates;
- return `Optional<T>` for an optional single result and collections for multiple results;
- encode ownership scope in queries when needed, for example `findByIdAndWorkspaceId`;
- use explicit JPQL or `@EntityGraph` when fetch shape or query complexity requires it;
- remain free of business decisions and HTTP concerns.

Avoid loading all records and filtering in Java. Avoid native SQL unless JPA cannot express the required behavior and the trade-off is documented.

### 5.4 Entity

Entities map the Flyway schema. Current conventions are:

- singular snake-case table names;
- UUID identifiers generated with `GenerationType.UUID`;
- `@Getter`, `@Setter`, and `@NoArgsConstructor`; never Lombok `@Data`;
- lazy, required `ManyToOne` parent references with non-null join columns;
- string enums with explicit database-compatible lengths and Java defaults where the column has a default;
- Jakarta constraints aligned with—not stronger than—the database unless a documented business rule requires it;
- `OffsetDateTime` for `TIMESTAMP WITH TIME ZONE`;
- `@CreationTimestamp` and `@UpdateTimestamp`, with creation fields not updatable;
- `columnDefinition = "TEXT"` where existing mappings represent PostgreSQL text columns;
- no parent collections unless a use case requires them.

Do not add generated `toString`, `equals`, or `hashCode` behavior that traverses lazy relationships. Do not use entities as API contracts.

### 5.5 DTO

DTOs define stable application boundaries. Although only a package placeholder exists today, new API work should use:

- intent-specific request names such as `CreateProjectRequest`;
- response names such as `ProjectResponse` or `ExecutionSummaryResponse`;
- API-relevant validation and no persistence annotations;
- identifiers rather than nested managed entities;
- immutable forms, preferably Java records, when compatible with the local Spring conventions established by the first implementation.

Do not place business logic, repositories, or entity lifecycle behavior in DTOs.

### 5.6 Mapper

Mappers translate between boundary DTOs and application/entity data. The mapper package is currently a placeholder, so the first implementation should establish a simple, explicit convention before adding a mapping framework.

Mappers should:

- avoid database access;
- avoid deciding authorization or business rules;
- avoid blindly copying identifiers, audit fields, versions, or relationships from client input;
- make null and enum handling visible;
- be covered by focused tests when mapping is non-trivial.

> **Rule:** Do not introduce MapStruct or another mapping framework solely for convenience in one small story. Such a dependency is an architectural choice requiring approval.

## 6. Package Organization

The current backend uses a layered package structure rooted at `com.automationstudio.api`:

| Package | Purpose |
|---|---|
| `config` | Framework and application configuration adapters. |
| `controller` | Inbound HTTP adapters. |
| `domain` | Shared domain enums and value concepts. |
| `dto` | Request and response boundary types. |
| `entity` | Jakarta Persistence entities. |
| `exception` | Application exceptions and error contracts. |
| `mapper` | Boundary mapping components. |
| `repository` | Spring Data JPA repositories. |
| `service` | Application use-case services. |

Follow this implemented organization for near-term backend stories. The architecture documentation describes future business-capability modules, while an older naming guide gives examples such as `com.automationstudio.project`. Do not reorganize existing packages during feature work. A move from layered packages to capability modules requires an approved ADR and migration plan.

Keep one public top-level Java type per file, match filename to type, and use lower-case package names. New modules outside `studio-api` must follow an approved module boundary rather than becoming miscellaneous shared code.

## 7. Coding Standards Summary

### Java and Spring

- Target Java 21 and the versions managed by the module POM.
- Use Jakarta (`jakarta.*`) APIs, not legacy `javax.*` imports.
- Prefer constructor injection for Spring components; do not use field injection.
- Keep methods cohesive and names intention-revealing.
- Use `UUID` for current entity and API identifiers.
- Use `OffsetDateTime` for persisted `TIMESTAMPTZ` fields.
- Prefer typed enums over free-form strings when the database has a fixed allowed set.
- Handle absence explicitly with `Optional` at repository single-result boundaries; do not use it for entity fields.
- Avoid speculative abstractions, generic “utility” dumping grounds, and premature frameworks.

### Persistence and validation

- Treat Flyway migrations as append-only after application.
- Keep `spring.jpa.hibernate.ddl-auto=validate`; schema creation belongs to Flyway.
- Mirror database nullability, length, uniqueness, enum values, foreign keys, and numeric constraints in mappings where JPA/Jakarta can represent them.
- Put cross-record and transition validation in services and durable constraints in migrations.
- Keep relationships lazy and unidirectional unless an observed use case justifies another shape.
- Check query counts and fetch plans when traversing relationships; avoid N+1 behavior.

### Naming

| Concern | Convention | Example |
|---|---|---|
| Story | `AS-XXX` | `AS-013` |
| Java type | PascalCase | `ProjectService` |
| Java member | camelCase | `workspaceId` |
| Database table/column | snake_case | `execution_step`, `created_at` |
| Repository | entity + `Repository` | `ProjectRepository` |
| Request DTO | action + subject + `Request` | `CreateProjectRequest` |
| Response DTO | subject + view + `Response` | `ExecutionSummaryResponse` |
| REST base | versioned plural resources | `/api/v1/projects` |
| Environment variable | UPPER_SNAKE_CASE | `DB_USERNAME` |

### Formatting and dependencies

- Match surrounding whitespace, import ordering, annotations, and line endings.
- Do not reformat unrelated files.
- Add dependencies only when acceptance criteria require them and existing platform capabilities are insufficient.
- Do not change the POM as a side effect of ordinary entity, repository, service, or controller work.

## 8. Feature Implementation Workflow

### Step 1: Establish scope

Extract the acceptance criteria, permitted files, prohibited files, expected output, and verification commands. Record any ambiguity that could materially change the design.

### Step 2: Inspect the neighborhood

Read repository instructions and the closest analogous implementation. For persistence work, read the complete migration chain affecting the table—not only the migration that created it. For API work, inspect existing route, error, DTO, and test styles once present.

### Step 3: Reconcile contracts

Compare the request with:

- schema types, nullability, constraints, indexes, and delete behavior;
- entity identifiers and relationships;
- stored enum values;
- application and architecture boundaries;
- public API compatibility;
- existing tests.

Stop and report irreconcilable conflicts before coding.

### Step 4: Design the smallest coherent change

Identify the exact files and tests required. Avoid “while here” cleanup. If a migration is required, create a new versioned migration; never rewrite a completed one.

### Step 5: Implement in dependency order

A typical vertical slice proceeds through schema/domain, entity/repository, service, mapper/DTO, controller, then tests and documentation. Only create layers needed by the story; do not scaffold speculative endpoints or abstractions.

### Step 6: Verify proportionally

Run focused tests first, then the module test suite. Review the diff, status, generated files, migration alignment, and scope. If infrastructure prevents a check, report the exact failed command and reason.

### Step 7: Report completion

State what changed, what was verified, assumptions, known limitations, and any pre-existing worktree changes. Do not claim success for a test that did not run.

## 9. AI Collaboration Workflow

Codex and other agents must work as transparent engineering collaborators:

1. **Understand.** Restate the outcome internally and identify hard constraints.
2. **Inspect.** Read instructions, relevant code, tests, migrations, and documentation before editing.
3. **Plan.** For non-trivial work, define a short sequence with one active step at a time.
4. **Communicate.** Give concise progress updates for tool-based or long-running work and surface blockers promptly.
5. **Implement.** Make focused edits using repository-native patterns.
6. **Verify.** Execute relevant checks and inspect the final diff.
7. **Self-review.** Look for scope creep, contract mismatches, weak tests, security issues, and accidental generated files.
8. **Hand off.** Provide an evidence-based completion report.

Codex must explain assumptions that affect behavior. Safe, local, reversible implementation details may follow existing conventions without interrupting the user. Decisions that change architecture, public contracts, schema semantics, security, or task scope require approval.

> **Important:** An AI agent must not conceal uncertainty with plausible code. A precise blocker is more valuable than an implementation that violates the schema or acceptance criteria.

## 10. Repository Inspection Checklist

Before editing, check what applies:

- [ ] Read root `AGENTS.md` and any nearer instruction file.
- [ ] Read the task and list permitted/prohibited files.
- [ ] Check `git status` without overwriting unrelated user changes.
- [ ] Locate analogous production code with `rg`/`rg --files`.
- [ ] Read relevant tests and fixtures.
- [ ] Read `pom.xml` before assuming a library or Java feature is available.
- [ ] For database work, read all migrations affecting the table.
- [ ] Compare JPA types, columns, constraints, defaults, relationships, and enum values to Flyway.
- [ ] Read relevant ADR and architecture sections.
- [ ] Distinguish implemented code from placeholder packages and future diagrams.
- [ ] Check configuration such as Open EntityManager in View, DDL mode, and Flyway locations.
- [ ] Identify API, migration, and backward-compatibility impact.
- [ ] Confirm no secrets or local machine paths will enter the diff.

Before finishing:

- [ ] Run focused tests and the appropriate module build.
- [ ] Review `git diff --check`, `git diff`, and `git status --short`.
- [ ] Verify only authorized files changed during the task.
- [ ] Report tests not run and blockers accurately.

## 11. Scope Control Rules

- Change only what is necessary to satisfy the current story.
- Preserve pre-existing worktree changes; assume they belong to the user.
- Do not combine feature work with broad cleanup, formatting, dependency upgrades, or architecture changes.
- Do not create adjacent repositories, DTOs, services, controllers, mappers, migrations, or tests unless required for the requested outcome.
- If a requested change exposes a separate defect, document it and ask whether it should become follow-up work.
- Generated build output is not a deliverable and must not be added to source control.
- Read-only inspection is allowed when relevant; external writes, commits, pushes, releases, or messages require explicit authorization.

## 12. Allowed Changes

Within a story's explicit scope, normal changes may include:

- implementing or correcting the requested production behavior;
- adding focused tests that prove acceptance criteria and regressions;
- creating a new Flyway migration when the approved story changes the schema;
- updating directly affected documentation;
- adding repository queries that follow Spring Data conventions;
- adding validation aligned with documented business rules and database constraints;
- performing small local refactors required to make the requested change safe and testable.

“Allowed” does not mean automatically required. The story's narrower file and action restrictions always win.

## 13. Forbidden Changes

Codex and contributors must not:

- rewrite unrelated code or reformat unrelated files;
- change architecture or package organization without approval;
- modify a completed Flyway migration;
- rename packages, modules, database objects, or public routes without approval;
- remove, disable, or weaken tests to make a build pass;
- introduce unnecessary frameworks, libraries, services, or infrastructure;
- perform broad refactoring during feature work;
- change public APIs or persisted enum values without migration and compatibility analysis;
- add speculative fields, endpoints, layers, abstractions, or future features;
- bypass services, authorization, audit, or approval boundaries;
- expose entities directly through HTTP contracts;
- store secret values in tables, code, logs, snapshots, artifacts, tests, or documentation;
- run automation workloads on an HTTP request thread when the runner boundary is implemented;
- let engines, AI modules, or MCP tools write authoritative state outside governed application services;
- commit, push, open a pull request, deploy, or publish unless explicitly requested.

## 14. Testing Requirements

The current test suite contains only a Spring Boot context-load test. That is a baseline, not a sufficient standard for new behavior.

| Change type | Minimum expected verification |
|---|---|
| Domain/service rule | Unit tests for success, invalid input, boundaries, and invalid transitions. |
| Repository query | `@DataJpaTest` or equivalent integration test against PostgreSQL-compatible behavior when query derivation, constraints, or ordering matter. |
| Controller/API | MVC tests for validation, status codes, error shape, and service interaction; integration tests for critical paths. |
| Entity/migration | Migration startup/validation plus persistence tests for constraints, mappings, defaults, enum values, and relationships. |
| Mapper | Unit tests for meaningful transformations and null/enum behavior. |
| Bug fix | A regression test that fails before and passes after the fix. |
| Documentation only | Link, heading, example, and formatting review; no backend build unless the documentation changes executable material. |

Use real PostgreSQL or a compatible container for database-specific features such as `TIMESTAMPTZ`, check constraints, UUID behavior, and Flyway migrations; do not assume an in-memory database proves PostgreSQL behavior.

Run commands from `backend/studio-api`:

```powershell
.\mvnw.cmd test
```

If the wrapper is unavailable due to the environment and Maven is installed, use:

```powershell
mvn test
```

For a faster compilation check, `mvn compile` is acceptable but does not replace tests. Never report “tests pass” when only compilation ran. Test data must not contain real credentials or depend on execution order.

## 15. Documentation Requirements

Update documentation when a story changes architecture, a public contract, database semantics, operating procedures, or an approved engineering convention. Keep updates in the owning document:

- architecture boundaries and flows in `docs/architecture/`;
- significant decisions and trade-offs in a numbered ADR;
- schema/domain guidance in `docs/database/` and immutable implementation in Flyway;
- engineering conventions in `docs/engineering/` or this playbook;
- delivery history in the appropriate development log or release document when the story requires it.

Use professional Markdown, descriptive headings, relative repository links, fenced code blocks with language identifiers, and tables only when they improve comparison. Label future-state designs explicitly. Do not duplicate a rule across many files without naming its authoritative owner.

Documentation-only stories must not be used to alter production behavior. If documentation and code disagree, record the discrepancy and resolve it through a scoped implementation or documentation decision.

## 16. Git Workflow

The documented branch model is:

| Purpose | Pattern |
|---|---|
| Stable releases | `main` |
| Integration | `develop` |
| Feature | `feature/AS-XXX-description` |
| Bug fix | `fix/BUG-XXX-description` or the currently approved issue form |
| Documentation | `docs/description` |
| Release | `release/vX.Y.Z` |

Use Conventional Commits with an optional scope:

```text
feat(api): add project management endpoints
fix(database): align execution status mapping
test(api): cover project ownership rules
docs(ai): add engineering playbook
```

Keep commits cohesive and reviewable. Before committing, inspect the staged diff and ensure generated files, secrets, IDE state, and unrelated changes are excluded. AI agents must not commit, push, rewrite history, switch branches destructively, or discard work unless the user explicitly authorizes that action.

## 17. Pull Request Expectations

A pull request should include:

- the story or issue identifier and business outcome;
- a concise description of the design and affected layers;
- explicit schema, API, security, and compatibility impact;
- tests added and exact commands run;
- documentation updated;
- assumptions, limitations, follow-up work, and known inconsistencies;
- migration and rollback/forward-fix considerations when applicable;
- screenshots or example requests/responses for user-visible behavior when useful.

Reviewers should verify architecture ownership, business-rule placement, workspace/project scoping, validation at appropriate layers, migration immutability, lazy-loading safety, query behavior, test quality, error handling, secret handling, and absence of unrelated changes.

PR titles should be traceable, for example `AS-013 Add project repository queries`. Approval and passing checks are required before merge. Do not self-approve by weakening the Definition of Done.

## 18. Definition of Done

A story is done only when all applicable items are true:

- [ ] Acceptance criteria are satisfied without unapproved scope expansion.
- [ ] Architecture and layer boundaries are preserved.
- [ ] Business rules and database constraints agree.
- [ ] Public API and backward-compatibility impacts are understood.
- [ ] Production code is readable and follows local conventions.
- [ ] Focused automated tests cover new behavior and important failure paths.
- [ ] Relevant tests and build checks pass.
- [ ] Documentation is updated where the behavior or decision changed.
- [ ] No credentials, secrets, generated output, or unrelated changes are included.
- [ ] The diff has been self-reviewed and is ready for human review.
- [ ] Assumptions, inconsistencies, and checks not run are disclosed.
- [ ] Required pull-request review is complete before merge.

## 19. Completion Report Template

```markdown
## Summary

- Implemented ...
- Preserved ...

## Files Changed

- `path/to/file`: reason

## Verification

- `command` — passed/failed/not run
- Manual or schema comparison — result

## Assumptions

- None, or list each behavior-affecting assumption.

## Inconsistencies / Limitations

- None, or identify the conflicting sources and impact.

## Scope Confirmation

- Only the authorized files were changed during this task.
- No commit or push was performed unless explicitly requested.
```

Reports must be factual. Include pre-existing worktree changes when they could make a blanket “only these files changed” statement misleading.

## 20. Common Mistakes to Avoid

| Mistake | Correct approach |
|---|---|
| Coding from the task text without reading migrations or analogous code | Inspect the complete affected implementation first. |
| Treating architecture diagrams as implemented features | Label target state and verify tracked source. |
| Editing an existing migration | Add a new forward migration after approval. |
| Mapping database UUIDs to numeric IDs | Follow the schema and existing `GenerationType.UUID` convention. |
| Letting Java enums drift from database check constraints | Compare every stored value before implementation. |
| Adding eager or bidirectional relationships for convenience | Keep lazy parent references and query the required fetch shape. |
| Using Lombok `@Data` on entities | Use the established getter/setter/no-args convention. |
| Returning entities from controllers | Use DTOs and explicit mapping. |
| Putting ownership or state-transition rules in controllers/repositories | Enforce them in application services and durable constraints where appropriate. |
| Assuming Bean Validation replaces database constraints | Use complementary boundary, service, and database validation. |
| Adding a framework for one small mapping or query | Prefer explicit code until a repeated need justifies an approved dependency. |
| Running only compilation and reporting that tests passed | State the exact command and result. |
| Reformatting or “cleaning up” unrelated files | Keep the diff focused and create follow-up work. |
| Overwriting a dirty worktree | Preserve user changes and inspect status/diff carefully. |

## 21. Future Expansion Guidelines

Future capabilities must extend the documented boundaries rather than collapse them:

- **Business-capability modules:** Introduce explicit application interfaces and ownership rules before reorganizing the current layered packages.
- **Runner:** Keep execution off API request threads; use durable claiming, leases, heartbeats, cancellation, bounded workspaces, and guarded finalization.
- **Engine plugins:** Version the contract, normalize events/results, isolate framework dependencies, and prohibit direct platform database access.
- **Events and outbox:** Use versioned envelopes, correlation and causation identifiers, non-secret payloads, transactional publication, and idempotent consumers.
- **Artifacts:** Keep bytes outside PostgreSQL behind a storage port; persist metadata and enforce authorized, short-lived access.
- **AI:** Keep analysis optional, advisory, evidence-grounded, redacted, provenance-rich, and separate from authoritative outcomes.
- **MCP:** Reuse application services and project-scoped authorization; require explicit human approval for mutating or destructive tools by default.
- **Security:** Introduce OIDC/scoped service identities and external secret references without storing secret values in domain records.
- **Scale:** Add brokers, object storage, isolated containers, runner pools, Kubernetes, high availability, or multi-tenancy only when measured requirements justify them.
- **Compatibility:** Version APIs, events, prompts, and engine contracts before independent consumers depend on them.

Every material architectural expansion requires an ADR describing context, decision, alternatives, consequences, migration path, and operational impact.

## 22. Appendix

### 22.1 Current technology baseline

| Concern | Repository evidence |
|---|---|
| Language | Java 21 (`backend/studio-api/pom.xml`) |
| Backend | Spring Boot parent 4.1.0, Spring Web MVC, Data JPA, Validation, Actuator |
| Persistence | Jakarta Persistence, Hibernate-managed audit timestamps, UUID identifiers |
| Database | PostgreSQL; local image `postgres:16-alpine` |
| Schema management | Flyway migrations `V1` through `V6` |
| Build | Maven wrapper and Maven compiler plugin with Lombok annotation processing |
| Runtime schema policy | `spring.jpa.hibernate.ddl-auto=validate` |
| Session policy | `spring.jpa.open-in-view=false` |
| Tests | JUnit/Spring Boot context-load test; test starters declared in the POM |

### 22.2 Known repository inconsistencies and gaps

These observations prevent future contributors from accidentally treating stale documentation as implemented truth:

1. `docs/database/domain-model.md` calls itself a source of truth for **Liquibase**, but the application uses Flyway and the same document later says Liquibase work is pending. The applied Flyway migrations are authoritative.
2. The architecture execution state diagram uses `QUEUED`, `PREPARING`, `COLLECTING`, `SUCCEEDED`, `TIMED_OUT`, and `INFRASTRUCTURE_ERROR`; the implemented `ExecutionStatus` and database constraint use `PENDING`, `CLAIMED`, `RUNNING`, `PASSED`, `FAILED`, `CANCELLED`, and `ERROR`.
3. Architecture documents describe a runner, engine plugins, outbox, web client, AI modules, MCP server, identity, reports, and artifact ports that are not present in tracked implementation.
4. The naming guide suggests business packages such as `com.automationstudio.project`; the implemented backend uses layered packages under `com.automationstudio.api`.
5. `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, several AI/engineering documents, `api-design.md`, `database-design.md`, and ADR-002 through ADR-004 are empty or heading-only placeholders.
6. The domain-model document names indexes with `ix_`/`ux_` forms that differ from actual Flyway `idx_` index and `uk_` unique-constraint names.
7. The domain model says application time should use an injected `Clock`; current entities use Hibernate audit annotations, and `Execution.requestedAt` initializes with `OffsetDateTime.now()`.
8. The domain model says final execution counts “should equal” passed, failed, and skipped totals; the current database check only enforces that their sum is less than or equal to `total_tests`.
9. The architecture promotes business-capability modules, while the current source is a small layered persistence foundation. This is an evolution gap, not permission for an incidental package rewrite.
10. The documented Definition of Done expects automated tests, but the current repository has only a context-load smoke test. New feature work should improve coverage incrementally.

### 22.3 Core references

- `docs/architecture/system-architecture.md`
- `docs/architecture/module-architecture.md`
- `docs/architecture/sequence-diagrams.md`
- `docs/architecture/deployment.md`
- `docs/adr/ADR-001-modular-engine-architecture.md`
- `docs/database/domain-model.md`
- `docs/engineering/naming-conventions.md`
- `docs/agile/definition-of-done.md`
- `backend/studio-api/pom.xml`
- `backend/studio-api/src/main/resources/db/migration/`

This playbook should evolve through focused documentation work as implementation matures. Update it when an approved, implemented convention makes a rule materially inaccurate; do not use it as a substitute for an ADR or migration.
