# AS-015: Automation Suite Management Software Requirements Specification

## 1. Status and Purpose

**Status:** Implemented; documentation reconciled with the delivered contract on 2026-07-19.

AS-015 delivers project-scoped management of platform-owned automation suites. This document is
the as-built contract for the database expansion, Java domain, REST API, validation, behavior,
and verification completed by AS-015A through AS-015F.

The implementation follows the naming and staged migration direction in
[ADR-005](../adr/ADR-005-platform-domain-naming-and-feature-first-architecture.md), including its
dated implementation-outcome amendment.

## 2. Scope and Architecture

- `AutomationSuite` is the Java and API domain term.
- It represents the existing logical suite aggregate, not a second suite resource or table.
- PostgreSQL continues to store the aggregate in the transitional `test_suite` table.
- `execution.test_suite_id` remains the transitional execution foreign-key column.
- Existing suite UUIDs and execution relationships are preserved.
- Production code remains in the repository's layer-first packages under
  `com.automationstudio.api`. Feature-first reorganization was not performed and is separate
  future work.
- `engineId` is an optional opaque string. No engine enum or Engine Registry exists.
- `SuiteType` and `AutomationSuiteStatus` are platform-owned enums, not engine identifiers.
- Configuration is persisted as a JSONB object without engine-specific interpretation.

Automation Test Cases, runner behavior, scheduling, adapters, authentication, authorization,
engine discovery, and engine execution are outside AS-015.

## 3. Implemented Data Contract

| Field | Request contract | Persistence and response behavior |
|---|---|---|
| `name` | Required, nonblank, maximum 150 characters | Stored in `test_suite.name`. |
| `description` | Optional | Stored as text. |
| `engineType` | Required, nonblank, maximum 50 characters | Required transitional field in `engine_type`. |
| `suiteReference` | Required, nonblank, maximum 300 characters | Required transitional field in `suite_reference`. |
| `engineId` | Optional string, maximum 100 characters | Nullable compatibility field in `engine_id`. |
| `suiteType` | Optional `SuiteType` enum | Nullable; values are `API`, `UI`, `MOBILE`, `PERFORMANCE`, `SECURITY`, and `DATABASE`. |
| `configuration` | Optional JSON object | Nullable JSONB; non-object top-level JSON is rejected by request binding or database constraints. |
| `status` | Optional on create; not present on PUT | `ACTIVE`, `INACTIVE`, or `ARCHIVED`; create defaults to `ACTIVE`. |
| `id` | Server-controlled | UUID primary key. |
| `projectId` | Server-controlled from the route | Existing owning Project. |
| `version` | Server-controlled | JPA `@Version` value; successful PUT and PATCH operations increment it. |
| `createdAt`, `updatedAt` | Server-controlled | Audit timestamps. |

PUT replaces the mutable metadata fields but cannot change status. Status changes use the explicit
PATCH endpoint. Clients do not submit a version in create, PUT, or PATCH requests.

## 4. REST API

Every operation is scoped by both Project and suite identity where applicable.

| Method | Path | Success | Purpose |
|---|---|---:|---|
| POST | `/api/v1/projects/{projectId}/automation-suites` | 201 | Create a suite. |
| GET | `/api/v1/projects/{projectId}/automation-suites` | 200 | List suites. |
| GET | `/api/v1/projects/{projectId}/automation-suites/{suiteId}` | 200 | Get one suite. |
| PUT | `/api/v1/projects/{projectId}/automation-suites/{suiteId}` | 200 | Update mutable metadata. |
| PATCH | `/api/v1/projects/{projectId}/automation-suites/{suiteId}/status` | 200 | Change lifecycle status. |
| DELETE | `/api/v1/projects/{projectId}/automation-suites/{suiteId}` | 204 | Physically delete a suite. |

The list endpoint accepts Spring `Pageable` query parameters such as `page`, `size`, and `sort`,
plus an optional `status` filter. It currently returns Spring's directly serialized `Page` JSON,
with page metadata at the response root. That representation is functional but is a known API
stability risk; a stable custom pagination envelope is deferred.

## 5. Examples

### Create request

```json
{
  "name": "Checkout smoke",
  "description": "Critical checkout scenarios",
  "engineType": "PLAYWRIGHT",
  "suiteReference": "suites/checkout-smoke",
  "engineId": "playwright-java",
  "suiteType": "UI",
  "configuration": {
    "browser": "chromium",
    "headless": true
  },
  "status": "ACTIVE"
}
```

`status` may be omitted and then defaults to `ACTIVE`. `engineId`, `suiteType`, `configuration`,
and `description` may also be omitted.

### Response

```json
{
  "id": "9d25cf28-4ba8-4e26-997d-7f1d06efb210",
  "projectId": "af13526c-63e5-4ab5-b565-a01f39962a78",
  "name": "Checkout smoke",
  "description": "Critical checkout scenarios",
  "engineType": "PLAYWRIGHT",
  "suiteReference": "suites/checkout-smoke",
  "engineId": "playwright-java",
  "suiteType": "UI",
  "configuration": {
    "browser": "chromium",
    "headless": true
  },
  "status": "ACTIVE",
  "version": 0,
  "createdAt": "2026-07-17T14:30:00Z",
  "updatedAt": "2026-07-17T14:30:00Z"
}
```

### PUT request

```json
{
  "name": "Checkout regression",
  "description": "Checkout regression coverage",
  "engineType": "PLAYWRIGHT",
  "suiteReference": "suites/checkout-regression",
  "engineId": "playwright-java",
  "suiteType": "UI",
  "configuration": {
    "browser": "chromium"
  }
}
```

### Status PATCH request

```json
{
  "status": "ARCHIVED"
}
```

## 6. Implemented Behavior

1. Suite names are unique within a Project. The same name may be used in different Projects.
2. Create verifies that the Project exists. Listing a missing Project returns 404.
3. Get, PUT, PATCH, and DELETE use project-scoped lookup. A suite owned by another Project is not
   disclosed and returns 404.
4. Listing supports pagination, sorting, and an optional lifecycle-status filter.
5. JPA optimistic versioning protects updates internally; successful PUT and PATCH operations
   increment the response version.
6. Delete physically removes the suite when database relationships allow it.
7. Configuration maps are defensively copied by the entity and mapper behavior is covered with
   the real generated MapStruct implementation.
8. `id`, ownership, version, and audit fields are ignored when request DTOs are mapped to entities.

## 7. Errors

Standard failures use `ApiErrorResponse`, containing timestamp, numeric status, HTTP error,
message, and request path.

| Condition | Status |
|---|---:|
| Validation failure, invalid enum/UUID, or malformed input | 400 |
| Missing Project, missing suite, or cross-Project suite access | 404 |
| Duplicate suite name within a Project | 409 |

Translation of a referenced-suite foreign-key delete failure and translation of optimistic-lock
failures into a stable 409 API contract are deferred. They must not be represented as delivered
behavior.

## 8. Database Migration Outcome

Flyway migration `V7__expand_test_suite_for_automation_suite.sql` expands the existing table. It
adds nullable `engine_id`, nullable `suite_type`, nullable JSONB `configuration`, and a nonnegative
`version` with a compatibility-safe default. It retains required legacy `engine_type` and
`suite_reference`, the three existing lifecycle statuses, existing suite UUIDs, and
`execution.test_suite_id`. It does not create a second suite table or perform destructive legacy
data conversion.

Physical table, foreign-key column, constraint, and index renaming remains a later
compatibility-gated migration.

## 9. Verification and Acceptance

- [x] Backward-compatible Flyway migration and populated legacy-row coverage.
- [x] Single `AutomationSuite` JPA entity mapped to transitional `test_suite`.
- [x] Project-scoped repository queries, uniqueness, pagination, sorting, and status filtering.
- [x] Service create, read, list, update, status-change, and delete behavior.
- [x] Project-scoped REST endpoints and `ApiErrorResponse` behavior.
- [x] Missing-project list contract returns 404.
- [x] Cross-Project suite access returns 404.
- [x] Controller MVC tests.
- [x] Real generated MapStruct mapper tests, including ignored server fields and configuration copies.
- [x] Domain and repository PostgreSQL integration tests.
- [x] Flyway migration integration tests.
- [x] Full HTTP-to-PostgreSQL REST API integration tests using PostgreSQL Testcontainers.
- [x] Create validation, default status, duplicate-name conflict, and same-name cross-Project behavior.
- [x] PUT and PATCH version increments and physical deletion of unreferenced suites.
- [x] Full Maven suite last known result before AS-015G: 116 tests passing.
- [ ] Referenced-suite delete-conflict translation.
- [ ] Optimistic-lock conflict API translation and any client version contract.
- [ ] Stable custom pagination response envelope.
- [ ] Authentication and authorization.
- [ ] Engine Registry validation.
- [ ] Engine-specific configuration-schema validation.
- [ ] Physical database table and foreign-key column renaming.
- [ ] Feature-first package reorganization.

## 10. Explicit Deferred Work

The unchecked items above are intentionally deferred and are not AS-015G documentation defects.
In particular, `engineId` must remain a string; future Engine Registry work must not replace it
with an engine enum. Referenced-delete and optimistic-lock API translations require deliberate
error-contract work. Pagination stabilization, security, schema validation, physical database
renaming, and package reorganization each require separate changes and review.
