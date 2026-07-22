# AS-017: Environment Management Software Requirements Specification

## 1. Status and Purpose

**Status:** AS-017A design complete; implementation is not started.

AS-017 defines Project-scoped management of target environments. A future execution will select
an Environment containing a base URL, non-secret runtime configuration, and references to secrets
that only the runner may resolve during a scoped execution.

This document is the requirements baseline for AS-017B through AS-017G. It evolves the existing
`environment` table, `Environment` entity, `EnvironmentStatus`, and `EnvironmentRepository`; it
does not define a second environment aggregate.

## 2. Existing Baseline and Reconciliation

Flyway V2 already created the singular `environment` table with:

| Existing column | Current contract | AS-017 disposition |
|---|---|---|
| `id` | UUID primary key | Preserve existing values; application-generated for new rows. |
| `project_id` | Required FK to `project(id)`, `ON DELETE RESTRICT` | Preserve. |
| `name` | Required `VARCHAR(100)` | Preserve. |
| `base_url` | Required `VARCHAR(500)` | Preserve and add absolute HTTP(S) validation. |
| `status` | Required `VARCHAR(30)`, default `ACTIVE` | Preserve; expand check from ACTIVE/INACTIVE to include ARCHIVED. |
| `created_at` | Required `TIMESTAMPTZ` | Preserve. |
| `updated_at` | Required `TIMESTAMPTZ` | Preserve. |

V2 also provides `uk_environment_project_name`, `idx_environment_project_id`, and the restrictive
`execution.environment_id -> environment.id` foreign key. All remain authoritative.

The current Java `Environment` maps those fields with a lazy required Project association,
string enum status, generated UUID, validation annotations, and Hibernate audit timestamps.
`EnvironmentStatus` currently contains ACTIVE and INACTIVE. `EnvironmentRepository` currently
extends `JpaRepository` without Project-scoped queries. AS-017C must evolve these types in place.

AS-017B must add, but AS-017A does not implement:

| New column | Planned PostgreSQL contract |
|---|---|
| `description` | `VARCHAR(1000)`, nullable. |
| `type` | `VARCHAR(30) NOT NULL`; constrained to the seven supported types. |
| `configuration` | `JSONB NOT NULL DEFAULT '{}'::jsonb`; root must be an object. |
| `secret_references` | `JSONB NOT NULL DEFAULT '{}'::jsonb`; root must be an object. |
| `is_default` | `BOOLEAN NOT NULL DEFAULT FALSE`. |
| `version` | `BIGINT NOT NULL DEFAULT 0`; nonnegative. |

The migration must backfill every existing Environment row to `TEST` before making `type`
mandatory. The current portfolio-stage system has no authoritative persisted classification
mapping, so this explicit compatibility value provides a deterministic, upgrade-safe result.
Users may correct classifications through the API after AS-017 is delivered. Existing rows also
receive empty JSON objects, false default flags, and version zero.

The migration must enforce that only ACTIVE Environments may be default:

```sql
CONSTRAINT chk_environment_default_active
    CHECK (is_default = FALSE OR status = 'ACTIVE')
```

It must also add the partial unique index:

```sql
CREATE UNIQUE INDEX uk_environment_project_default
    ON environment (project_id)
    WHERE is_default = TRUE;
```

## 3. Scope

AS-017 includes:

- Project-scoped Environment CRUD, lifecycle changes, and default selection.
- Environment classification, description, absolute base URL, and optimistic versioning.
- Non-secret JSON configuration and external secret-reference JSON.
- Pagination, sorting, and status, type, and default filters.
- Database and service enforcement of at most one default per Project.
- Physical deletion only when no Execution references the Environment.
- Safe standard errors, PostgreSQL migrations, and layered automated verification.

## 4. Explicit Out of Scope

- Runner or Playwright execution and test-case selection.
- Secret-provider integration, resolution, existence checks, or credential validation.
- Target URL health checks and environment health monitoring.
- Engine-specific configuration-schema validation.
- Environment cloning or environment-variable substitution.
- Frontend implementation.
- Authentication and authorization implementation.
- AI-authored authoritative changes; AI remains advisory and non-authoritative.

## 5. Environment Data Model

| Field | Type | Required | Contract |
|---|---|---:|---|
| `id` | UUID | Yes | Server-generated and immutable. |
| `projectId` | UUID | Yes | Existing owning Project; server-controlled from route. |
| `name` | String | Yes | Trimmed, nonblank, maximum 100, unique within Project. |
| `description` | String | No | Maximum 1,000 characters. |
| `baseUrl` | String | Yes | Trimmed absolute HTTP or HTTPS URL, maximum 500. |
| `type` | EnvironmentType | Yes | Required classification. |
| `configuration` | JSON object | Yes | Non-secret runtime values; defaults to `{}`. |
| `secretReferences` | JSON object | Yes | External references only; defaults to `{}`. |
| `status` | EnvironmentStatus | Yes | Defaults to ACTIVE. |
| `isDefault` | boolean | Yes | Defaults to false. |
| `version` | long | Yes | Server-controlled optimistic-lock version returned in responses. |
| `createdAt` | OffsetDateTime | Yes | Server-controlled. |
| `updatedAt` | OffsetDateTime | Yes | Server-controlled. |

Supported `EnvironmentType` values are `LOCAL`, `DEV`, `TEST`, `QA`, `STAGING`, `UAT`, and
`PRODUCTION`. Supported lifecycle values are `ACTIVE`, `INACTIVE`, and `ARCHIVED`.

Create may set mutable business fields, status, and `isDefault`; omitted status and default flag
use their defaults. PUT replaces name, description, base URL, type, configuration, and
secretReferences. Lifecycle and default changes use their dedicated PATCH operations. Requests
cannot assign identity, ownership, persisted version, or timestamps. For PUT, status PATCH,
default PATCH, and DELETE, the client supplies the expected current version through `If-Match`;
that precondition checks concurrency but never assigns the JPA-managed version.

## 6. Project Isolation and Uniqueness

Every Environment belongs to exactly one Project. Every operation first establishes that the
route Project exists, then accesses Environment data through both `projectId` and `environmentId`.

1. Listing a missing Project returns 404.
2. A missing Environment returns 404.
3. An Environment owned by another Project returns 404 without disclosing its existence.
4. Create cannot assign ownership independently of the route.
5. Update cannot move an Environment to another Project.
6. Names are case-sensitive and unique after trimming within one Project.
7. Different Projects may use the same Environment name.
8. The database unique constraint remains authoritative for concurrent writes and maps to 409.

Authentication and authorization are deferred, but must later apply this same Project boundary.

## 7. Default-Environment Invariant

A Project may have zero or one default Environment. Only an ACTIVE Environment may be default.

- Creating an Environment with `isDefault=true` requires ACTIVE status.
- Setting an ACTIVE Environment as default clears any prior default in the same Project and then
  sets the target, atomically in one transaction.
- Setting `isDefault=false` clears the target and leaves the Project with no default.
- Repeating the already-satisfied default state is idempotent and returns the current resource.
- Changing the default Environment to INACTIVE or ARCHIVED clears its default flag in the same
  transaction.
- Changing a non-default Environment's status does not affect another Environment's default.
- Deleting the default leaves the Project without a default; no replacement is selected.
- An INACTIVE or ARCHIVED Environment cannot be made default and returns 409 Conflict because the
  requested state violates the lifecycle invariant.

Default changes must serialize per Project. The service implementation will lock the owning
Project row before reading or changing defaults, use a deterministic Project-first lock order,
and rely on the PostgreSQL partial unique index as the final concurrent-write safeguard. A partial
unique-index violation maps to 409 without leaving two defaults. The service enforces the
ACTIVE-only rule before persistence, while `chk_environment_default_active` provides database
defence in depth. A violation of that check constraint maps to 409.

## 8. Lifecycle Behaviour

- `ACTIVE`: eligible for default selection and future execution selection.
- `INACTIVE`: temporarily unavailable and never default.
- `ARCHIVED`: retained for history and never default.

Create defaults to ACTIVE. PUT does not change status. Status PATCH accepts all three values.
Archiving is not deletion: uniqueness and ownership still apply. AS-017 does not define Project
status propagation or future execution eligibility beyond the default invariant.

## 9. Configuration Boundary

`configuration` is contractually restricted to non-secret runtime configuration. The root JSON
value must be an object. Arrays, strings, numbers, booleans, null roots, and malformed JSON return
400. Nested JSON values may otherwise be any valid JSON type because engine-specific
interpretation is deferred.

The service recursively rejects the following case-insensitive secret-shaped keys at any nesting
depth with 400: `password`, `passwd`, `secret`, `clientSecret`, `apiKey`, `accessToken`,
`refreshToken`, and `privateKey`. Key screening is defence in depth, not proof that arbitrary JSON
is secret-free: secret material can use an unrecognized key or appear in a value. Clients remain
responsible for sending credentials through `secretReferences`, never `configuration`. Validation
errors and logs identify the field/key path when safe but never include the rejected value or
serialize either JSON payload.

Each configuration object has a maximum serialized UTF-8 size of 65,536 bytes (64 KiB), measured
after JSON parsing using the API's canonical serializer. This is a new explicit bounded-input
rule; existing suite and test-case JSON contracts do not currently define a byte limit. Oversized
configuration returns 400. The database JSONB object check remains a defense in depth.

Example:

```json
{
  "browser": "chromium",
  "locale": "en-GB",
  "timeoutSeconds": 30,
  "headless": true
}
```

## 10. Secret-Reference Boundary and Security

Plaintext passwords, tokens, API keys, client secrets, private keys, cookies, or other secret
values are prohibited from persistence, logs, responses, errors, and execution history.
`secretReferences` is the required mechanism for credentials, stores locators only, and defaults
to `{}`. Because arbitrary data cannot be reliably classified as secret or non-secret, the system
does not claim that configuration key screening can prove the absence of secrets.

The root must be an object. Each property value must be a nonblank string with an opaque URI-style
scheme prefix (for example `vault:` or `arn:`); arrays, nested objects, nulls, numbers, booleans,
and unqualified strings are rejected. The control plane validates only this structural distinction
between a reference and an obvious literal. It does not allowlist providers, parse provider-owned
locator content, verify existence, or resolve the reference. Each object has the same 65,536-byte
serialized UTF-8 limit as configuration.

```json
{
  "username": "vault://orangehrm/qa/username",
  "password": "vault://orangehrm/qa/password"
}
```

API responses may return the stored references because they are configuration metadata, but must
never contain resolved values. Logs and error messages should use only Project and Environment
identifiers, never serialize either JSON object. Secret resolution belongs exclusively to the
future runner during a scoped execution, and resolved values must not enter snapshots.

## 11. Base URL and Input Validation

| Input | Rule |
|---|---|
| `projectId`, `environmentId` | Valid UUIDs. |
| `name` | Trimmed, nonblank, maximum 100. |
| `description` | Optional, maximum 1,000. |
| `baseUrl` | Trimmed, maximum 500, absolute URI with `http` or `https`, a nonempty host, and no user-info or fragment. |
| `type` | One supported EnvironmentType. |
| `status` | One supported EnvironmentStatus. |
| `configuration` | Required/defaulted JSON object, contractually non-secret, at most 64 KiB; recursively rejects prohibited secret-shaped keys case-insensitively. |
| `secretReferences` | Required/defaulted object of opaque reference strings, at most 64 KiB. |
| `isDefault` | Boolean; true only with ACTIVE status. |
| `If-Match` | Required for PUT, both PATCH operations, and DELETE; exactly one quoted nonnegative decimal version, for example `"3"`. |

Unknown enums, invalid UUIDs, malformed JSON, and validation failures return 400. URI validation
does not perform DNS resolution, network access, normalization that changes the supplied target,
or health checks.

## 12. REST API Contract

Base path:

```text
/api/v1/projects/{projectId}/environments
```

| Method | Path | Success | Purpose |
|---|---|---:|---|
| POST | Base path | 201 | Create an Environment. |
| GET | Base path | 200 | Page Project Environments. |
| GET | `/{environmentId}` | 200 | Read one Environment. |
| PUT | `/{environmentId}` | 200 | Replace mutable metadata and JSON configuration. |
| PATCH | `/{environmentId}/status` | 200 | Change lifecycle status. |
| PATCH | `/{environmentId}/default` | 200 | Set or clear the default flag. |
| DELETE | `/{environmentId}` | 204 | Delete an unreferenced Environment. |

POST returns a `Location` header for the new nested resource. List supports Spring `page`, `size`,
and `sort` parameters plus optional `status`, `type`, and `isDefault` filters; filters combine with
logical AND. Default ordering is `name ASC, id ASC`. AS-017 initially follows the existing suite
convention of serializing Spring `Page` directly; a stable custom page envelope is separate work.

PUT, status PATCH, default PATCH, and DELETE require an `If-Match` request header containing the
expected Environment version as one quoted nonnegative decimal value:

```http
If-Match: "3"
```

Weak validators, wildcards, unquoted values, negative values, multiple values, and non-decimal
values are malformed and return 400. POST and GET do not require `If-Match`. The service compares
the supplied version with the current Environment version before mutation; a mismatch returns 409.
The value is a concurrency precondition only and does not assign the persisted version. JPA
`@Version` remains server controlled. Successful PUT and PATCH responses contain the newly
persisted version. DELETE returns 204 with no response body after validating the expected version.

Status PATCH body:

```json
{"status":"ARCHIVED"}
```

Default PATCH body:

```json
{"isDefault":true}
```

Responses include all model fields, including stored references and `version`, but never resolved
secret values.

## 13. Errors and Concurrency

Standard failures use the existing safe `ApiErrorResponse` shape.

| Condition | HTTP |
|---|---:|
| Validation failure, invalid enum/UUID, prohibited configuration key, malformed/non-object/oversized JSON | 400 |
| Missing or malformed required `If-Match` | 400 |
| Missing Project, missing Environment, or cross-Project access | 404 |
| Duplicate Project-scoped Environment name | 409 |
| Attempt to make a non-ACTIVE Environment default | 409 |
| ACTIVE-default check constraint, concurrent default invariant, or database uniqueness conflict | 409 |
| Environment deletion blocked by Execution history | 409 |
| `If-Match` version mismatch or JPA optimistic-lock failure | 409 |
| Successful deletion | 204 |

Environment uses JPA `@Version`. Concurrent stale modifications must map to a stable 409 response
without exposing internal exception, SQL, constraint details, or rejected JSON values. The
server-owned version is returned for observability. Mutation clients supply the expected version
through `If-Match`, but requests never assign it. Database constraints remain authoritative when
concurrent requests bypass a service pre-check.

## 14. Persistence and Delete Behaviour

AS-017B must use the next Flyway version after V8 and alter the existing table additively. It must
preserve all existing Environment UUIDs, Project relationships, names, base URLs, timestamps,
Execution rows, and the `ON DELETE RESTRICT` execution foreign key. It must not edit an applied
migration.

An Environment with no Execution reference may be physically deleted. An Environment referenced
by any Execution must not be deleted; the application translates this protected deletion to 409.
The database restrictive foreign key is the final safeguard. ARCHIVED is the retention path for an
Environment that must remain available for historical interpretation. Deleting the current default
does not select a replacement.

Future execution work must snapshot the selected Environment identity, type, base URL, non-secret
configuration, and relevant mutable metadata required for reproducibility. It may snapshot secret
references where policy permits, but never resolved values.

## 15. Acceptance Criteria

- [x] AS-017A requirements and ADR acknowledge and evolve the existing Environment schema/entity.
- [x] Existing and planned columns, constraints, indexes, and compatibility rules are explicit.
- [x] Project isolation, missing-Project listing, cross-Project 404, and scoped uniqueness are defined.
- [x] The zero-or-one default invariant and ACTIVE-only/default-clearing rules are defined.
- [x] The ACTIVE-default service rule, database check, partial unique index, and 409 mappings are defined.
- [x] Configuration and secret-reference object boundaries, recursive prohibited-key screening,
  limitations, and 64 KiB limits are defined.
- [x] Credentials require secret references; rejected values and JSON payloads stay out of logs/errors.
- [x] REST operations, filters, success codes, and error outcomes are defined.
- [x] `If-Match` parsing and required mutation coverage, new-version responses, execution-reference
  deletion conflict, and optimistic-lock 409 behavior are defined.
- [x] AS-017B through AS-017G have a staged plan.
- [x] Out-of-scope work is explicit.
- [ ] AS-017B through AS-017F implementation and tests are delivered.
- [ ] AS-017G reconciles these documents with the implemented contract.

## 16. Staged Implementation Plan

### AS-017B: Database migration and migration tests

- Alter the existing `environment` table; do not create a duplicate.
- Add description, type, configuration, secret references, default flag, and version.
- Backfill every legacy type to TEST, expand the status check, add JSON/version checks, add
  `chk_environment_default_active`, and add the partial default index.
- Preserve execution history and restrictive foreign keys.
- Test empty and AS-016-populated upgrade paths, TEST backfill, ACTIVE-default constraint failures,
  and constraint-to-409 integration with PostgreSQL Testcontainers.

### AS-017C: Entity, repository, and persistence tests

- Evolve `Environment`, add `EnvironmentType`, and expand `EnvironmentStatus`.
- Add JSONB defensive-copy mappings and `@Version`.
- Add Project-scoped queries, combined filters, uniqueness queries, default lookup, and required
  locking queries.
- Verify constraints, isolation, JSONB, audit fields, version increments, and legacy-row mapping.

### AS-017D: Service layer and default rules

- Implement scoped CRUD, normalization, validation, lifecycle, and filters.
- Implement Project-first serialization for default changes.
- Parse and require expected versions for every update/PATCH/delete service operation, reject
  mismatches, and retain server-controlled JPA versioning.
- Recursively reject prohibited configuration keys without logging values or payloads.
- Clear defaults on status transition and translate delete/default/name/version/constraint conflicts.
- Add service unit and PostgreSQL concurrency tests, including stale expected versions.

### AS-017E: REST API, DTOs, mapper, and MVC tests

- Add nested routes, request/response DTOs, status/default DTOs, and MapStruct mapping.
- Enforce URL, JSON shape/size, recursive prohibited-key screening, reference structure, and
  server-controlled field boundaries.
- Require and parse `If-Match` on PUT, status PATCH, default PATCH, and DELETE only.
- Add pagination, sorting, filters, standard errors, successful new-version assertions, and MVC
  coverage for missing, malformed, matching, and stale `If-Match` values.

### AS-017F: Full REST/PostgreSQL integration tests

- Exercise HTTP through Spring, Flyway, JPA, and PostgreSQL.
- Cover CRUD, isolation, filters, lifecycle, default replacement/clearing, conflicts, JSON, URL
  validation, recursive prohibited keys, ACTIVE-default database enforcement, `If-Match`, JPA
  optimistic locking, restrictive deletion, and concurrent default requests.
- Run the complete Maven suite.

### AS-017G: Documentation reconciliation and branch review

- Reconcile this SRS, ADR-007, development log, and relevant domain documentation with delivery.
- Review the complete branch diff and confirm migration safety and no secret exposure.
- Run formatting checks and the complete verification suite; record actual evidence only.

## 17. Risks and Deferred Decisions

- Direct Spring `Page` serialization is an existing API-stability risk.
- Secret-provider allowlists and provider-specific reference validation are deferred.
- Authentication, authorization, and field-level reference visibility require future security work.
- Immutable execution snapshot design remains future execution-plane work.
- Large or deeply nested JSON may require global parser depth/complexity limits beyond the byte cap.
