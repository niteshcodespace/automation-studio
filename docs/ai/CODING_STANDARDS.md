# Automation Studio Coding Standards

This document defines the official coding standards for Automation Studio. It is specific to the current repository and its approved direction: Java 21, Spring Boot, Jakarta APIs, Spring Data JPA, PostgreSQL, Flyway, Lombok, and a layered backend under `com.automationstudio.api`.

Use the [AI Engineering Playbook](./CODEX.md) for architecture, workflow, scope, testing policy, and delivery expectations. Use the [Prompt Engineering Guide](./PROMPT_GUIDE.md) to frame implementation and review requests. This document focuses on how repository code should be written.

> **Authority:** Existing compiling implementation and applied Flyway migrations are authoritative for current conventions. Sections covering controllers, services, DTOs, mappers, and centralized errors establish the standard for their first implementation because those packages currently contain placeholders only.

## Table of Contents

1. [Introduction](#1-introduction)
2. [Coding Philosophy](#2-coding-philosophy)
3. [Java Standards](#3-java-standards)
4. [Spring Boot Standards](#4-spring-boot-standards)
5. [Package Standards](#5-package-standards)
6. [Entity Standards](#6-entity-standards)
7. [Repository Standards](#7-repository-standards)
8. [Service Standards](#8-service-standards)
9. [Controller Standards](#9-controller-standards)
10. [DTO Standards](#10-dto-standards)
11. [Mapper Standards](#11-mapper-standards)
12. [Flyway Standards](#12-flyway-standards)
13. [Testing Standards](#13-testing-standards)
14. [Logging Standards](#14-logging-standards)
15. [Error Handling Standards](#15-error-handling-standards)
16. [Performance Guidelines](#16-performance-guidelines)
17. [Security Guidelines](#17-security-guidelines)
18. [Documentation Standards](#18-documentation-standards)
19. [Anti-Patterns](#19-anti-patterns)
20. [Examples: Bad, Better, Best](#20-examples-bad-better-best)
21. [Appendix](#21-appendix)

## 1. Introduction

Automation Studio is building an enterprise-quality control plane for projects, environments, test suites, executions, steps, and evidence. Consistent code is essential because persistence, execution, future engine plugins, and optional AI/MCP capabilities must evolve without eroding boundaries.

These standards apply repository-wide where the technology is relevant. Current concrete evidence comes from `backend/studio-api`; future modules should adopt their own approved local standards when their language or runtime differs.

### 1.1 Standard language

| Term | Meaning |
|---|---|
| **Must / must not** | Required for new or modified code. |
| **Should / should not** | Expected unless a documented reason justifies an exception. |
| **May** | Optional and context-dependent. |
| **Current convention** | Demonstrated in compiling repository code. |
| **First-implementation standard** | Governs a planned layer that does not yet have production classes. |

### 1.2 Precedence

When a story, schema, code, and documentation disagree, follow the source-of-truth precedence in `CODEX.md`. Do not change a stable convention inside unrelated feature work. Propose material standard changes through focused documentation and, when architectural, an ADR.

## 2. Coding Philosophy

Automation Studio code should be:

- **readable:** intent is visible from names, types, and control flow;
- **focused:** each type and method has one clear responsibility;
- **explicit:** business rules, state changes, and ownership checks are not hidden;
- **layered:** web, application, persistence, and mapping concerns remain separate;
- **schema-aligned:** Java mappings match the final Flyway-managed PostgreSQL schema;
- **testable:** dependencies and time sources can be controlled in tests;
- **safe by default:** secrets, authorization scope, and errors receive deliberate handling;
- **evolution-friendly:** public and persisted contracts change intentionally and compatibly.

Prefer simple code that matches the repository over a generic abstraction that serves only a hypothetical future. A small amount of explicit mapping or orchestration is better than an unnecessary framework or inheritance hierarchy.

```text
HTTP request
    -> Controller (boundary)
    -> Service (use case and transaction)
    -> Repository (persistence)
    -> PostgreSQL (durable constraints)

Entity <-> Mapper <-> DTO
```

## 3. Java Standards

### 3.1 Language and APIs

- Target Java 21 as configured in `backend/studio-api/pom.xml`.
- Use Jakarta packages (`jakarta.persistence`, `jakarta.validation`) rather than legacy `javax` packages.
- Use Java platform types before adding dependencies for equivalent behavior.
- Keep one public top-level type per source file; filename and public type must match.
- Do not use preview language features unless the build explicitly enables them through an approved change.

### 3.2 Naming

| Element | Convention | Example |
|---|---|---|
| Package | lower-case, no underscores | `com.automationstudio.api.repository` |
| Class/record/enum | PascalCase | `ExecutionStepStatus` |
| Method/field/parameter | camelCase | `findByIdAndWorkspaceId` |
| Constant | UPPER_SNAKE_CASE | `DEFAULT_PAGE_SIZE` |
| Boolean method | question-oriented prefix | `existsBySlug`, `isTerminal`, `hasAccess` |
| Request DTO | action + subject + `Request` | `CreateProjectRequest` |
| Response DTO | subject/view + `Response` | `ProjectResponse` |
| Service | subject/use case + `Service` | `ProjectService` |
| Repository | entity + `Repository` | `ProjectRepository` |
| Controller | resource + `Controller` | `ProjectController` |
| Test | type/behavior + `Test` or `IT` | `ProjectServiceTest`, `ProjectRepositoryIT` |

Use one term for one concept. In Java, follow domain names already established by schema and entities: `Workspace`, `Project`, `Environment`, `TestSuite`, `Execution`, `ExecutionStep`, and `ExecutionArtifact`.

### 3.3 Formatting

- Use four spaces for Java indentation; do not introduce tabs into new Java source.
- Use braces for all multi-line control structures.
- Keep annotations directly above the element they annotate.
- Group imports in the local style: application imports, Jakarta/Spring imports, Java imports, Lombok/Hibernate imports as formatting tools produce them. Remove unused imports.
- Keep lines readable; wrap long annotation arguments in the established entity style.
- Add one blank line between logical field/method groups, not between every statement.
- Do not reformat unrelated code or normalize line endings as part of a feature.

No formatter or static-analysis plugin is currently configured. Until one is approved, the nearest analogous source file is the formatting reference.

### 3.4 Types and nullability

- Prefer primitives for required values with meaningful primitive defaults only when that default is valid, such as JPA `long version` initialized to zero.
- Use wrapper types for nullable database columns and optional request fields.
- Use `UUID` for current entity identifiers and related boundary identifiers.
- Use `OffsetDateTime` for PostgreSQL `TIMESTAMP WITH TIME ZONE` mappings.
- Use `long`/`Long` for millisecond durations and byte sizes, matching PostgreSQL `BIGINT`.
- Do not return `null` collections. Return an empty collection when no records exist.

### 3.5 Records

Records are preferred for immutable request/response DTOs and small immutable value carriers when:

- component-based JSON binding is suitable;
- identity and behavior are value-based;
- inheritance or proxying is not required;
- construction invariants are clear.

Do not use records for JPA entities. Do not force a record when a framework or evolving compatibility requirement needs a conventional class. Because no DTO implementation exists yet, the first DTO story should confirm serialization and validation conventions with tests.

```java
public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String description,
        ProjectStatus status) {
}
```

### 3.6 Enums

- Place shared persisted domain enums in `com.automationstudio.api.domain`.
- Use singular PascalCase type names and UPPER_SNAKE_CASE constants.
- Persist JPA enums with `@Enumerated(EnumType.STRING)`; never rely on ordinals.
- Keep persisted values exactly aligned with Flyway check constraints.
- Give a field a Java default when the database column has the same semantic default and new entities require it.
- Treat adding, renaming, or removing a persisted enum constant as a schema and compatibility change.

Do not add display labels, HTTP parsing, or transition logic directly to a persisted enum unless that behavior is cohesive and tested. Cross-entity transition decisions belong in services/domain policy.

### 3.7 Optional

- Use `Optional<T>` for repository or service return values where one result may be absent.
- Do not use `Optional` for entity fields, DTO fields, method parameters, or collection elements.
- Do not call `Optional.get()` without first proving presence.
- Convert absence into an application exception at the service boundary, not in the controller.
- Use empty collections for zero-to-many results.

```java
Project project = projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
        .orElseThrow(() -> new ProjectNotFoundException(projectId, workspaceId));
```

### 3.8 Exceptions

- Use unchecked, application-specific exceptions for service-layer business failures.
- Name exceptions by meaning, such as `ProjectNotFoundException` or `DuplicateProjectNameException`.
- Preserve the original cause when translating infrastructure exceptions.
- Include safe, diagnostic context such as non-secret identifiers.
- Do not use exceptions for normal zero-to-many results or expected branch control.
- Do not catch `Exception` merely to log and rethrow it.
- Never expose stack traces, SQL, credentials, or internal implementation messages to API clients.

## 4. Spring Boot Standards

### 4.1 Component design and injection

- Use constructor injection for Spring components.
- Declare injected dependencies `private final`.
- Prefer a single explicit constructor or Lombok `@RequiredArgsConstructor` consistently once the first service/controller convention is established.
- Do not use field injection or retrieve application components from the context manually.
- Keep components stateless; request-specific mutable state belongs in method variables or durable storage.

```java
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }
}
```

### 4.2 Configuration

- Put framework configuration in `com.automationstudio.api.config`.
- Prefer typed `@ConfigurationProperties` for related custom settings over scattered `@Value` fields.
- Use environment placeholders for deployment-specific values.
- Never hard-code production credentials, tokens, hosts, or filesystem paths.
- Preserve `spring.jpa.open-in-view=false`; services must load/map required data inside transaction boundaries.
- Preserve `spring.jpa.hibernate.ddl-auto=validate`; Flyway owns schema creation and evolution.
- Keep environment-specific configuration outside source-controlled secrets.

The local datasource defaults in `application.properties` are development conveniences, not production credential standards.

### 4.3 Transactions

- Put transaction boundaries on public service methods, not controllers or repositories.
- Use `@Transactional(readOnly = true)` for read-only use cases.
- Use `@Transactional` for use cases that create or mutate durable state.
- Keep transactions as short as the consistent use case allows.
- Do not perform network calls, engine execution, long file operations, or user interaction inside database transactions.
- Do not rely on self-invocation to activate Spring transactional proxies.
- Use optimistic locking where concurrent updates can overwrite authoritative state; `Execution` currently uses `@Version`.

### 4.4 Validation

Validation is layered:

| Layer | Responsibility | Examples |
|---|---|---|
| DTO/controller boundary | Shape and local input constraints | `@NotBlank`, `@Size`, `@PositiveOrZero`, `@Valid` |
| Service | Cross-record and lifecycle rules | workspace ownership, project consistency, valid transitions |
| Entity | Persistence-compatible field constraints | nullability, maximum length, non-negative fields |
| Database | Durable integrity | `NOT NULL`, `UNIQUE`, `CHECK`, foreign keys |

- Use Jakarta Validation annotations.
- Do not make Java validation stricter than the database unless it represents an approved business rule.
- Do not use DTO validation as authorization.
- Use `@Validated` only where method-level validation is deliberately required.
- Return consistent validation errors through centralized exception handling.

## 5. Package Standards

The current backend uses layered packages under `com.automationstudio.api`:

| Package | Content |
|---|---|
| root | Spring Boot application entry point only |
| `config` | Spring/framework configuration |
| `controller` | REST inbound adapters |
| `domain` | Domain enums and cohesive value concepts |
| `dto` | API/application boundary records or classes |
| `entity` | Jakarta Persistence entities |
| `exception` | Application exceptions and API error handling |
| `mapper` | DTO/entity/application mapping |
| `repository` | Spring Data JPA interfaces and persistence adapters |
| `service` | Transactional application use cases |

Use the current structure for near-term backend work. Do not introduce parallel feature packages such as `com.automationstudio.project` during ordinary feature delivery. A package reorganization requires an approved architecture decision and migration plan.

Package rules:

- dependencies flow controller -> service -> repository/entity;
- mappers may know DTOs and entities but must not call repositories;
- repositories must not depend on controllers, DTOs, or services;
- entities must not depend on controller/service types;
- domain types must not depend on web or persistence adapters unless they are explicitly persisted enums under the current model;
- avoid `common`, `util`, `helper`, or `misc` packages without a narrowly defined owner.

## 6. Entity Standards

### 6.1 Schema alignment

Every entity change must be compared with the final schema produced by all applicable Flyway migrations. Match:

- table and column names;
- PostgreSQL and Java types;
- nullability and lengths;
- defaults and allowed enum values;
- unique/check constraints where JPA metadata can represent them;
- foreign keys and delete behavior;
- indexes relevant to query patterns.

Applied migrations remain unchanged. If code and schema conflict, report the conflict or add an approved forward migration.

### 6.2 Identifier convention

Current entities use application-generated UUID identifiers:

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;
```

Do not substitute `Long`, identity columns, database sequences, or string IDs without an approved schema change. Explicit `@Column(name = "id")` is optional when the default maps exactly, but new entities should match the nearest established entity style.

### 6.3 Lombok and construction

Current entities use:

```java
@Getter
@Setter
@NoArgsConstructor
```

- Do not use `@Data` on entities.
- Do not use Lombok `@Value` or records for entities.
- Add constructors/builders only for a demonstrated use case and ensure JPA retains an accessible no-argument constructor.
- Do not let generated methods traverse lazy relationships.

### 6.4 Relationships

Required parent references follow this convention:

```java
@NotNull
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "workspace_id", nullable = false)
private Workspace workspace;
```

- Default parent references to `FetchType.LAZY`.
- Keep relationships unidirectional unless a use case requires navigation from both sides.
- Do not add parent collections preemptively.
- Match `optional` and `nullable` to the schema.
- Do not enable cascade operations or orphan removal unless aggregate ownership and database delete behavior explicitly support them.
- Avoid serializing managed relationships.

### 6.5 Columns and validation

- Map non-default snake-case names explicitly with `@Column(name = "...")`.
- Set `nullable = false` and `length` when the schema defines them.
- Use `@NotBlank` for required human-entered strings and `@Size(max = ...)` for schema bounds.
- Use `@PositiveOrZero` for nullable or required non-negative counts/durations as applicable.
- Map PostgreSQL `TEXT` consistently with `columnDefinition = "TEXT"`; do not invent a VARCHAR length.
- Use wrapper numeric types for nullable columns.
- Do not add validation that rejects values allowed by the database without an approved business rule.

### 6.6 Timestamps and audit fields

Current `TIMESTAMP WITH TIME ZONE` columns map to `OffsetDateTime`.

```java
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private OffsetDateTime createdAt;

@UpdateTimestamp
@Column(name = "updated_at", nullable = false)
private OffsetDateTime updatedAt;
```

- Use the established Hibernate annotations for entity audit fields.
- Preserve creation timestamps with `updatable = false`.
- Keep nullable lifecycle timestamps nullable when the schema permits.
- Use an injected `Clock` for new service-level business time decisions.
- Do not casually mix `Instant`, `LocalDateTime`, and `OffsetDateTime` for the same persisted concept.

`Execution.requestedAt` currently initializes with `OffsetDateTime.now()`. Do not copy direct system-clock calls into new service business logic; migrate time handling only through scoped work.

### 6.7 Enum persistence

```java
@NotNull
@Enumerated(EnumType.STRING)
@Column(name = "status", nullable = false, length = 30)
private ExecutionStepStatus status = ExecutionStepStatus.PENDING;
```

Enum constants, migration check constraints, defaults, and Java field defaults must agree exactly.

### 6.8 Equality, hash codes, and string representation

- Do not use Lombok-generated all-field `equals`, `hashCode`, or `toString` on entities.
- Do not include lazy relationships or mutable collections in these methods.
- Prefer no override until entity identity semantics are explicitly required.
- If equality is required, document proxy/transient-entity behavior and test it. Do not assume a generated UUID exists before persistence.
- Log entity identifiers and selected scalar fields, not complete entity objects.

### 6.9 Lazy loading

`spring.jpa.open-in-view=false` is intentional. Therefore:

- initialize required data within service transactions;
- map entities to DTOs before leaving the transactional use case;
- use repository fetch plans for required relationships;
- never solve `LazyInitializationException` by enabling Open EntityManager in View or changing every relationship to eager.

## 7. Repository Standards

### 7.1 Interface convention

Repositories use Spring Data JPA:

```java
public interface ProjectRepository extends JpaRepository<Project, UUID> {
}
```

- Place interfaces in `com.automationstudio.api.repository`.
- Use the entity identifier type exactly.
- Do not annotate standard Spring Data interfaces with `@Repository` unless a concrete implementation requires it.
- Keep business decisions out of repositories.

### 7.2 Derived queries

Use derived method names for simple, readable predicates:

```java
boolean existsByWorkspaceIdAndName(UUID workspaceId, String name);

List<Project> findAllByWorkspaceId(UUID workspaceId);

Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
```

- Encode ownership scope in the query when access is scoped.
- Match method cardinality to the return type.
- Use `existsBy...` for existence checks rather than loading an entity.
- Include ordering in the method name only when it remains readable.
- Switch to explicit JPQL when a derived name becomes difficult to understand.

### 7.3 Optional and collections

- Return `Optional<Entity>` for zero-or-one results.
- Return `List<Entity>` only when the result is bounded or naturally small.
- Return `Page<Entity>` or `Slice<Entity>` for user-facing or potentially unbounded collections.
- Do not return `null`.
- Do not use `Optional<List<T>>`.

### 7.4 Pagination and sorting

- Accept `Pageable` for potentially large list endpoints.
- Define deterministic default ordering at the service/controller boundary.
- Cap page size at the API boundary when pagination is exposed.
- Prefer `Slice` when the total count is not needed and count cost matters.
- Avoid unpaged full-table reads for executions, artifacts, logs, or history.

### 7.5 EntityGraph and fetch plans

Use `@EntityGraph` when a use case needs known relationships and the query remains simple:

```java
@EntityGraph(attributePaths = "workspace")
Optional<Project> findWithWorkspaceById(UUID id);
```

- Fetch only what the use case needs.
- Name methods to communicate the expanded fetch shape.
- Test query behavior where N+1 or lazy initialization is a risk.
- Do not globally change relationships to eager loading.

### 7.6 Custom queries

- Use JPQL for joins, projections, aggregations, or conditions that derived names cannot express clearly.
- Bind all values with named parameters.
- Prefer DTO/interface projections for read-heavy views that do not require managed entities.
- Use native SQL only for PostgreSQL-specific behavior or proven query needs that JPQL cannot express.
- Document why a native query is necessary and cover it with PostgreSQL integration tests.
- Keep modifying queries explicit with `@Modifying` and execute them within a service transaction.

Do not place authorization policy or state-transition decisions inside query strings.

## 8. Service Standards

Services are the application layer and the home of use-case coordination.

### 8.1 Structure

- Place services in `com.automationstudio.api.service`.
- Name public methods after use cases, not generic framework operations.
- Inject repositories, mappers, clocks, and published application interfaces through constructors.
- Keep services free of HTTP request/response types.
- Keep methods cohesive; split distinct use cases rather than growing one mode-driven method.

### 8.2 Transaction boundaries

- Annotate public read operations `@Transactional(readOnly = true)`.
- Annotate public write operations `@Transactional`.
- Load, validate, mutate, and map within the transaction when lazy data is required.
- Avoid holding transactions open across external I/O.
- Let unexpected runtime failures roll back; define checked-exception behavior deliberately if introduced.

### 8.3 Business rules

Services enforce rules that span fields or records, including:

- a project belongs to the requested workspace;
- an environment and test suite belong to the selected project;
- names are unique within their database-defined scope;
- execution status transitions are permitted;
- terminal outcomes are not silently reversed;
- result counts and timestamps are logically consistent before persistence.

Use database constraints as the final defense against races. Pre-checks improve error clarity but do not replace unique or check constraints.

### 8.4 Validation and errors

- Treat validated DTO data as structurally valid, not authorized or business-valid.
- Translate missing records to meaningful application exceptions.
- Translate expected constraint conflicts to stable application errors where possible.
- Do not expose Spring Data or Hibernate exceptions through the service API.
- Include safe identifiers in errors and logs.

### 8.5 Return values

- Return DTOs/application models at boundaries used by controllers.
- Do not return a managed entity solely so a controller can navigate it.
- Return `Optional` only when absence is part of the service contract; otherwise throw a meaningful not-found exception.
- Do not return mutable internal collections directly.

## 9. Controller Standards

No production controller exists yet. The following standards govern the first and subsequent REST controllers.

### 9.1 Structure

- Place controllers in `com.automationstudio.api.controller`.
- Use `@RestController` and a versioned class-level `@RequestMapping`.
- Inject one or more application services through constructors.
- Accept/return DTOs, never JPA entities.
- Do not call repositories, manage transactions, or implement business rules.
- Keep endpoint methods small enough to see boundary validation and delegation at a glance.

### 9.2 REST naming

- Use `/api/v1` as the current documented base.
- Use plural, lower-case resource nouns.
- Model ownership in paths when it is part of the public contract, for example `/api/v1/workspaces/{workspaceId}/projects`.
- Use path variables for resource identity and query parameters for filtering, pagination, and sorting.
- Do not put verbs in ordinary CRUD paths.
- Use explicit command subresources/actions only for non-CRUD operations such as execution cancellation when approved.

### 9.3 DTO usage and validation

```java
@PostMapping
public ResponseEntity<ProjectResponse> create(
        @PathVariable UUID workspaceId,
        @Valid @RequestBody CreateProjectRequest request) {
    ProjectResponse response = projectService.create(workspaceId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

- Apply `@Valid` to request bodies.
- Apply constraints to DTO components/fields; do not rely on entity validation as the HTTP contract.
- Do not bind client input directly onto managed entities.
- Keep read-only and server-owned fields out of request DTOs.

### 9.4 HTTP status codes

| Situation | Status |
|---|---|
| Successful read/update | `200 OK` |
| Resource created | `201 Created`, with `Location` when practical |
| Asynchronous execution accepted | `202 Accepted` |
| Successful operation with no body | `204 No Content` |
| Malformed or validation-invalid request | `400 Bad Request` |
| Unauthenticated | `401 Unauthorized` |
| Authenticated but not authorized | `403 Forbidden` |
| Resource absent within the requested scope | `404 Not Found` |
| Uniqueness/state/version conflict | `409 Conflict` |
| Unexpected server failure | `500 Internal Server Error` without internal details |

Do not return `200` with an error payload. Do not reveal whether an out-of-scope tenant resource exists when authorization policy requires scoped not-found behavior.

### 9.5 Exception handling

- Centralize exception-to-HTTP mapping in `com.automationstudio.api.exception` with `@RestControllerAdvice`.
- Use Spring `ProblemDetail` as the initial error representation unless an approved API contract chooses another standard.
- Include stable problem type/title/status/detail and safe request correlation metadata.
- Keep controller methods free of repeated try/catch translation.
- Test the error contract through MVC tests.

## 10. DTO Standards

No production DTO exists yet. New DTOs must:

- live in `com.automationstudio.api.dto`;
- represent one boundary purpose;
- use request/response suffixes;
- prefer immutable records when compatible with serialization and validation;
- expose IDs and scalar/nested response DTOs, not managed entities;
- use Jakarta Validation on request components;
- omit persistence, Lombok entity, and transaction annotations;
- avoid accepting audit timestamps, versions, statuses, or ownership identifiers that the server controls unless the use case explicitly permits them;
- keep nullable and optional fields distinguishable according to update semantics.

Example:

```java
public record CreateProjectRequest(
        @NotBlank @Size(max = 120) String name,
        String description) {
}
```

For partial updates, define explicit semantics for absent versus explicit null. Do not introduce a generic map-based patch contract without validation and compatibility design.

Response DTOs should expose API-stable values. Avoid leaking storage locations, internal versions, lazy proxies, database constraint names, or secrets.

## 11. Mapper Standards

No production mapper exists yet. Use explicit mapper components before introducing a mapping framework.

- Place mappers in `com.automationstudio.api.mapper`.
- Keep mapping deterministic and side-effect free.
- Do not perform repository calls, authorization checks, clock reads, or business validation in a mapper.
- Map relationships to IDs or nested DTOs deliberately.
- Do not copy client-controlled ID, audit, status, version, or ownership fields into entities unless the use case authorizes them.
- Use separate create/update methods when their field ownership differs.
- Test non-trivial null, enum, and nested mapping.

```java
@Component
public class ProjectMapper {

    public ProjectResponse toResponse(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getWorkspace().getId(),
                project.getName(),
                project.getDescription(),
                project.getStatus());
    }
}
```

Because Open EntityManager in View is disabled, invoke relationship-reading mappings inside a transaction or fetch the required relationship explicitly.

## 12. Flyway Standards

### 12.1 Ownership and naming

- Flyway is the sole schema-evolution mechanism.
- Store migrations in `backend/studio-api/src/main/resources/db/migration/`.
- Name versioned files `V<next>__<lower_snake_case_description>.sql`.
- Inspect all existing migrations before selecting the next version.
- Use PostgreSQL-compatible SQL and singular snake-case table names.

### 12.2 Immutability

- Never modify, rename, reorder, or delete an applied migration.
- Correct released schema through a new forward migration.
- Do not change migration checksums to hide history changes.
- Preserve migration execution on both empty databases and databases upgraded through the complete chain.

### 12.3 Schema design

- Use UUID primary keys for current domain entities; application code generates values.
- Define nullability explicitly.
- Use named `fk_`, `uk_`, and `chk_` constraints consistent with current migrations.
- Use `idx_` for ordinary indexes consistent with current migrations.
- Store enum-like statuses as bounded VARCHAR values with check constraints.
- Use `TIMESTAMP WITH TIME ZONE` and database defaults for audit timestamps.
- Add indexes for foreign keys and demonstrated access paths.
- Define delete behavior deliberately; the current final model favors `ON DELETE RESTRICT` for business history.
- Add data backfill before setting a new column `NOT NULL` on existing tables.

### 12.4 Entity synchronization

The same story should align migration, enum, entity, validation, repository behavior, and tests when they form one schema change. Compare every mapped column against the final migration chain. Hibernate remains in `validate` mode; do not use automatic DDL to compensate for missing migrations.

### 12.5 Rollback philosophy

Flyway versioned migrations in this repository are forward-only. Design migrations to be safely deployable and recover through a corrective forward migration. Document operational rollback or compatibility phases for destructive/high-risk changes; do not assume down migrations exist.

## 13. Testing Standards

Current coverage contains only `StudioApiApplicationTests.contextLoads()`. New behavior requires focused coverage; the smoke test is not evidence for service, repository, API, or migration semantics.

### 13.1 Test levels

| Test type | Use for | Key expectations |
|---|---|---|
| Plain unit test | Domain/service/mapper logic | Fast, deterministic, no Spring context unless required |
| MVC slice test | Controller routes and errors | Validation, status, body, service interaction |
| JPA slice/integration test | Repositories and mappings | Derived/custom query behavior, constraints, fetch plans |
| Migration integration test | Flyway schema | Clean migration chain, upgrade behavior, PostgreSQL constraints |
| Application integration test | Critical vertical slice | Real wiring across relevant layers |
| Context smoke test | Application bootstrapping | Starts successfully; not a behavior test |

Use PostgreSQL-compatible tests for UUID, `TIMESTAMPTZ`, check constraints, native queries, and Flyway behavior. An in-memory database is not sufficient evidence for PostgreSQL-specific semantics.

### 13.2 Naming and structure

- Name test classes after the subject and test level.
- Use descriptive method names that state behavior and condition, for example `findByIdAndWorkspaceId_returnsEmptyForDifferentWorkspace`.
- Structure tests as arrange, act, assert without mandatory comments when the phases are obvious.
- Keep one reason for failure per test.
- Prefer behavior assertions over implementation-interaction assertions, except at isolated boundaries.

### 13.3 Coverage expectations

For each changed behavior, cover:

- the normal success path;
- absence or invalid input;
- boundary values;
- ownership/tenant scoping;
- uniqueness or concurrency conflicts where relevant;
- permitted and prohibited state transitions;
- serialization and error contracts for API work.

Every bug fix should include a regression test that fails before the fix. Do not remove, disable, loosen, or rewrite a valid test merely to make the build green.

### 13.4 Test data and time

- Use builders/factories only when repetition justifies them.
- Keep fixtures minimal and explicit.
- Do not depend on test ordering or shared mutable state.
- Use fixed/injected clocks for time-sensitive service behavior.
- Use synthetic credentials and redact secret-shaped test data.
- Clean database state through transaction rollback or controlled fixtures.

### 13.5 Commands and reporting

Run focused tests first, then from `backend/studio-api`:

```powershell
.\mvnw.cmd test
```

Use `mvn test` only when the wrapper cannot run and Maven is available. Report the exact command and result. Compilation alone does not mean tests passed.

## 14. Logging Standards

No application logging convention is implemented beyond Spring defaults. New application components must use SLF4J through the logging API supplied by Spring Boot.

- Declare one logger per class, or use Lombok `@Slf4j` consistently after the first logging implementation establishes local style.
- Use parameterized messages: `log.info("Execution {} started", executionId)`.
- Do not use string concatenation for log arguments.
- Log events at a level appropriate to actionability.

| Level | Use |
|---|---|
| `ERROR` | Unexpected failure requiring operational attention; include the exception once |
| `WARN` | Recoverable anomaly, rejected transition, retry, or degraded behavior |
| `INFO` | Significant lifecycle or audit-relevant event, not every repository call |
| `DEBUG` | Diagnostic flow and safe technical context |
| `TRACE` | Very detailed local troubleshooting; avoid in normal operation |

Include stable identifiers where relevant: request/correlation ID, workspace ID, project ID, execution ID, and attempt ID. Never log passwords, tokens, cookies, authorization headers, secret values, full model prompts containing sensitive context, or unrestricted artifact contents.

Do not log and rethrow the same exception at every layer. Log at the boundary that handles or terminates it. Avoid logging complete entities because relationships, large text, or sensitive fields may be traversed.

## 15. Error Handling Standards

Errors must be meaningful internally, stable at public boundaries, and safe to expose.

### 15.1 Flow

```text
Repository/infrastructure failure
    -> service translates expected domain condition
    -> application exception
    -> centralized controller advice
    -> stable ProblemDetail response
```

### 15.2 Application errors

- Define exceptions in `com.automationstudio.api.exception`.
- Separate absence, validation/business-rule failure, conflict, authorization, and unexpected failure.
- Do not expose Hibernate, JDBC, SQL, constraint names, stack traces, or package names to clients.
- Preserve causes for diagnosis.
- Make client-facing messages actionable but not sensitive.
- Attach correlation identifiers through the error response/log context when correlation support is implemented.

### 15.3 Database conflicts

Pre-check duplicate names where it improves UX, but retain and handle database uniqueness constraints for races. Translate only recognized constraint violations; unknown data-integrity failures should remain internal server errors and be logged safely.

### 15.4 Validation errors

Return field-level violations in a consistent structure built by centralized advice. Do not expose rejected secret values. Keep validation error ordering deterministic when tests or clients depend on it.

## 16. Performance Guidelines

Performance work must be evidence-led, but common persistence risks should be avoided during implementation.

- Keep `FetchType.LAZY` on parent relationships.
- Fetch required associations explicitly with `@EntityGraph`, JPQL fetch joins, or projections.
- Map to DTOs within transaction boundaries.
- Paginate unbounded resource collections and history.
- Use database predicates instead of loading/filtering in Java.
- Use `existsBy...` for existence checks.
- Align frequent filters/orderings with Flyway indexes after verifying query plans for material workloads.
- Avoid N+1 queries; test or inspect SQL for list/detail use cases with relationships.
- Avoid selecting artifact bodies or large text when only metadata is required.
- Keep transactions short and avoid external I/O inside them.
- Batch writes only after measuring a real need and testing transaction/failure semantics.
- Do not add caches before defining ownership, invalidation, consistency, and measurement.

Performance reviews must distinguish measured problems from inferred risk. Do not trade correctness or tenancy isolation for micro-optimization.

## 17. Security Guidelines

### 17.1 Data and secrets

- Never commit, persist, log, return, or embed secret values.
- Environment records may contain configuration and future secret references, not credentials.
- Treat artifact storage locations as internal references, not unrestricted client-supplied paths.
- Validate and bound all external input.
- Avoid mass assignment by mapping explicit request fields.

### 17.2 Authorization scope

- Enforce workspace/project scope in service use cases and repository queries.
- Do not trust ownership IDs from request bodies when path/authenticated context determines ownership.
- Do not reveal cross-workspace resource existence where scoped `404` behavior is appropriate.
- Future REST, MCP, and web adapters must reuse the same application authorization rules.

### 17.3 SQL, files, and output

- Use Spring Data parameter binding; never concatenate user input into JPQL/native SQL.
- Normalize and constrain filesystem operations inside approved roots when artifact/workspace features are implemented.
- Set safe content types and disposition for artifacts.
- Escape/encode user-controlled content in presentation layers.
- Keep error responses free of internal details.

### 17.4 AI and execution boundaries

- AI output is advisory and cannot overwrite authoritative execution outcomes.
- Redact sensitive context before provider invocation and preserve provenance/audit metadata.
- Engine plugins must not write platform tables or resolve arbitrary secrets.
- Mutating MCP/agent actions require governed services and the documented approval model.

## 18. Documentation Standards

- Use professional Markdown with one H1, a clear heading hierarchy, and language-tagged code fences.
- Use tables for comparison, not as a substitute for readable prose.
- Use relative links between repository documents.
- Mark current implementation and future design explicitly.
- Keep examples compilable or label them as illustrative/pseudocode.
- Use exact Automation Studio terminology and paths.
- Update the authoritative owner of a rule instead of copying policy across multiple documents.
- Record significant architectural decisions in ADRs.
- Document public APIs, migrations, configuration, and operational changes when implemented.
- Never put real credentials, internal tokens, personal paths, or sensitive production data in documentation.

`CODEX.md` owns engineering workflow and architecture-preservation rules. `PROMPT_GUIDE.md` owns prompt construction. This file owns coding conventions; link to the other guides instead of repeating their full content.

Java package documentation may use `package-info.java` for concise package intent, consistent with existing placeholder packages.

## 19. Anti-Patterns

| Anti-pattern | Why it is harmful | Required alternative |
|---|---|---|
| Lombok `@Data` on a JPA entity | Generates relationship-sensitive equality/string methods | Use `@Getter`, `@Setter`, `@NoArgsConstructor` |
| Eager relationships by default | Hides query cost and creates N+1/over-fetching risk | Lazy relationships plus explicit fetch plans |
| Bidirectional collections “for convenience” | Couples aggregates and complicates serialization/lifecycle | Add only for a proven use case |
| Entity returned from a controller | Leaks persistence shape and lazy proxies | Map to response DTO |
| Repository called by controller | Bypasses transaction and business rules | Delegate to a service |
| Business logic in repository query | Hides domain decisions in persistence | Service rule plus focused repository predicate |
| Field injection | Hides dependencies and harms testing | Constructor injection |
| `Optional` entity/DTO field | Poor JPA/serialization semantics | Nullable field with explicit boundary semantics |
| `Optional.get()` | Unsafe absence handling | `orElseThrow`, `map`, or explicit branch |
| Editing an applied migration | Breaks schema history/checksums | New forward migration |
| Hibernate automatic schema updates | Diverges environments from versioned schema | `ddl-auto=validate` plus Flyway |
| Persisting enum ordinals | Renumbering corrupts meaning | `EnumType.STRING` plus database check |
| Direct `OffsetDateTime.now()` in service rules | Makes tests non-deterministic | Injected `Clock` |
| Unpaged execution history | Unbounded memory/query cost | `Pageable`, deterministic ordering |
| Catching `Exception` and returning 200 | Hides failures and breaks HTTP semantics | Typed exceptions and centralized advice |
| Logging complete request/entity | May expose secrets and traverse relationships | Structured safe identifiers |
| Generic base CRUD service/controller | Obscures distinct business rules | Use-case-specific services and endpoints |
| New framework for one use case | Adds long-term complexity | Explicit code with existing dependencies |

## 20. Examples: Bad, Better, Best

The progression below shows how Automation Studio code should improve from unsafe convenience to explicit repository-specific design.

### 20.1 Project lookup

#### Bad

```java
public Project getProject(UUID id) {
    return projectRepository.findById(id).get();
}
```

Problems: unsafe `get()`, no workspace scope, returns a managed entity, and gives no meaningful error.

#### Better

```java
public Project getProject(UUID workspaceId, UUID projectId) {
    return projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
            .orElseThrow(() -> new ProjectNotFoundException(projectId, workspaceId));
}
```

Improvement: absence and workspace scope are explicit, but the service still exposes a managed entity.

#### Best

```java
@Transactional(readOnly = true)
public ProjectResponse getProject(UUID workspaceId, UUID projectId) {
    Project project = projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
            .orElseThrow(() -> new ProjectNotFoundException(projectId, workspaceId));

    return projectMapper.toResponse(project);
}
```

Why best: scope, transaction, error semantics, and boundary mapping are explicit.

### 20.2 Entity relationship

#### Bad

```java
@Data
@Entity
public class Project {
    @ManyToOne
    private Workspace workspace;
}
```

#### Better

```java
@Getter
@Setter
@NoArgsConstructor
@Entity
public class Project {
    @ManyToOne(fetch = FetchType.LAZY)
    private Workspace workspace;
}
```

#### Best

```java
@Entity
@Table(name = "project")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;
}
```

Why best: Lombok is entity-safe and relationship optionality, loading, column, and validation match the schema.

### 20.3 Repository collection query

#### Bad

```java
List<Project> projects = projectRepository.findAll().stream()
        .filter(project -> project.getWorkspace().getId().equals(workspaceId))
        .toList();
```

#### Better

```java
List<Project> projects = projectRepository.findAllByWorkspaceId(workspaceId);
```

#### Best

```java
Page<Project> projects = projectRepository.findAllByWorkspaceId(
        workspaceId,
        PageRequest.of(page, size, Sort.by("name").ascending()));
```

Why best for an unbounded API list: filtering, ordering, and pagination happen in the database. For a known-small internal collection, the “better” form may be the correct choice.

### 20.4 Controller boundary

#### Bad

```java
@PostMapping("/projects")
public Project create(@RequestBody Project project) {
    return projectRepository.save(project);
}
```

#### Better

```java
@PostMapping("/projects")
public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
    return projectService.create(request);
}
```

#### Best

```java
@PostMapping("/workspaces/{workspaceId}/projects")
public ResponseEntity<ProjectResponse> create(
        @PathVariable UUID workspaceId,
        @Valid @RequestBody CreateProjectRequest request) {
    ProjectResponse response = projectService.create(workspaceId, request);
    URI location = URI.create("/api/v1/workspaces/" + workspaceId
            + "/projects/" + response.id());
    return ResponseEntity.created(location).body(response);
}
```

Why best: resource ownership, DTO validation, service delegation, creation status, and resource location are visible.

### 20.5 Flyway evolution

#### Bad

```text
Edit V2__create_execution_schema.sql after it has been applied.
```

#### Better

```text
Add V7__add_execution_external_reference.sql.
```

#### Best

```sql
-- Add nullable data first to preserve upgrades with existing rows.
ALTER TABLE execution
    ADD COLUMN external_reference VARCHAR(150);

CREATE INDEX idx_execution_external_reference
    ON execution (external_reference);
```

Why best: the example preserves history and considers upgrade safety. Whether the column should be unique or non-null must come from approved requirements, not assumption.

### 20.6 Logging

#### Bad

```java
log.info("Starting execution " + execution);
```

#### Better

```java
log.info("Starting execution {}", execution.getId());
```

#### Best

```java
log.info("Execution started: executionId={}, projectId={}",
        execution.getId(), execution.getProject().getId());
```

Why best: the event is searchable and uses safe identifiers, provided the relationship is already loaded in the transaction. Do not trigger extra database queries solely for logging.

## 21. Appendix

### 21.1 Current backend baseline

| Concern | Current standard/evidence |
|---|---|
| Java | Java 21 |
| Spring | Spring Boot 4.1.0 parent; MVC, Data JPA, Validation, Actuator |
| Persistence | Jakarta Persistence and Hibernate |
| Database | PostgreSQL, Flyway migrations |
| IDs | `UUID` with `GenerationType.UUID` |
| Entity Lombok | `@Getter`, `@Setter`, `@NoArgsConstructor` |
| Relationships | Lazy, unidirectional required `ManyToOne` parents |
| Enums | String persistence with database checks |
| Time | `OffsetDateTime`; Hibernate creation/update timestamps |
| Schema mode | `ddl-auto=validate` |
| Session mode | Open EntityManager in View disabled |
| Repository | `JpaRepository<Entity, UUID>`, derived queries |
| Tests | Spring Boot context smoke test currently; focused tests required for new behavior |

### 21.2 First-implementation decisions

The following standards are intentionally defined here because corresponding production packages are placeholders:

- constructor-injected service and controller components;
- immutable record DTOs when compatible;
- explicit mapper components without a new framework;
- versioned `/api/v1` REST resources;
- centralized `@RestControllerAdvice` using Spring `ProblemDetail`;
- SLF4J parameterized logging;
- focused unit, MVC, JPA, migration, and integration tests.

If implementation reveals a framework constraint or approved API contract that requires a different convention, update this document in the same focused decision/change rather than silently diverging.

### 21.3 New-code checklist

- [ ] Package and layer are correct.
- [ ] Names use established domain terminology.
- [ ] Dependencies use constructor injection.
- [ ] DTOs, entities, and persistence concerns remain separate.
- [ ] JPA mappings match the final Flyway schema.
- [ ] UUID, enum, timestamp, relationship, and audit conventions are preserved.
- [ ] Transactions and business rules live in services.
- [ ] Repository queries are scoped, readable, and bounded.
- [ ] Controllers return DTOs with correct HTTP semantics.
- [ ] Errors are typed, centralized, and safe.
- [ ] Logs use safe identifiers and reveal no secrets.
- [ ] Tests cover success, boundaries, failure, and ownership.
- [ ] Documentation and examples remain accurate.

### 21.4 Core references

- [AI Engineering Playbook](./CODEX.md)
- [Prompt Engineering Guide](./PROMPT_GUIDE.md)
- `backend/studio-api/pom.xml`
- `backend/studio-api/src/main/java/com/automationstudio/api/`
- `backend/studio-api/src/main/resources/application.properties`
- `backend/studio-api/src/main/resources/db/migration/`
- `backend/studio-api/src/test/`

These standards should evolve deliberately as implemented patterns mature. Consistency with verified repository behavior takes priority over stylistic preference.
