# ADR-007: Project-Scoped Environment Management

## Status

Proposed

## Context

Automation Studio already has an `environment` table and a minimal `Environment` JPA entity from
AS-007/AS-008. They store Project ownership, name, base URL, ACTIVE/INACTIVE status, and audit
timestamps. Executions already reference Environment with `ON DELETE RESTRICT`.

Future executions need a deliberate target classification, non-secret runtime configuration, and
external secret references. The control plane must manage that mutable configuration without
storing resolved credentials or breaking historical Execution relationships. It also needs a
race-safe way to identify at most one preferred Environment per Project.

## Decision

### Evolve the existing aggregate

AS-017 evolves the singular existing `environment` table and `Environment` entity. Existing IDs,
Project relationships, names, base URLs, timestamps, uniqueness, and Execution foreign keys are
preserved. No parallel environment model or table is introduced.

An additive Flyway migration after V8 will add description, type, configuration, secret
references, default flag, and optimistic version; expand status to include ARCHIVED; and add the
required checks and partial unique index. Applied migrations remain immutable.

Every existing row is backfilled to `TEST`. The current portfolio-stage system has no
authoritative persisted classification mapping, so TEST is the explicit compatibility value that
preserves deterministic upgrades. Users may correct classifications through the API after AS-017
is delivered.

### Data and lifecycle

Environment has the model defined by the AS-017 SRS: a generated UUID, required Project, bounded
name/description/base URL, required classification, two JSON objects, ACTIVE/INACTIVE/ARCHIVED
status, default flag, optimistic version, and server audit timestamps.

Type is a platform-owned classification enum: LOCAL, DEV, TEST, QA, STAGING, UAT, and PRODUCTION.
It is not an execution engine enum. Base URL must be an absolute HTTP(S) URI with a host and no
embedded user information or fragment. Health checks and engine-specific validation are not part
of this decision.

ACTIVE means selectable and eligible to be default. INACTIVE is temporarily unavailable.
ARCHIVED is retained for historical use. Transitioning the default away from ACTIVE clears its
default flag atomically.

### Project isolation

All repository, service, and REST access is Project-scoped. A route Project must exist; listing a
missing Project and accessing an Environment from another Project return 404. Name uniqueness
remains `(project_id, name)`, allowing the same name in different Projects. Ownership cannot be
changed through update.

### Default invariant and concurrency

A Project has zero or one default Environment, and only an ACTIVE Environment can be default.
Setting a default clears its predecessor in the same transaction. Clearing or deleting it leaves
no default; the platform does not select a replacement.

Default mutations acquire a pessimistic lock on the Project before Environment rows. This provides
one stable serialization point even when the Project currently has no default row. The database
also enforces the invariant with:

```sql
CREATE UNIQUE INDEX uk_environment_project_default
    ON environment (project_id)
    WHERE is_default = TRUE;
```

The partial index is an authoritative defense against races or writes outside the normal service
path. The service also enforces that only ACTIVE Environments may be default, and the migration
adds database defence in depth:

```sql
CONSTRAINT chk_environment_default_active
    CHECK (is_default = FALSE OR status = 'ACTIVE')
```

Violations of either the partial index or ACTIVE-default check map to 409.

### Configuration and secret references

`configuration` is non-secret JSONB and `secret_references` is reference-only JSONB. Both are
non-null objects defaulting to `{}` and each is limited to 64 KiB of serialized UTF-8 JSON at the
API/service boundary. Database checks require object roots.

Configuration contents remain engine-neutral and are not interpreted by the control plane. They
are contractually non-secret, but arbitrary JSON cannot be proven free of secrets. As defence in
depth, the service recursively and case-insensitively rejects `password`, `passwd`, `secret`,
`clientSecret`, `apiKey`, `accessToken`, `refreshToken`, and `privateKey` keys with 400. This screen
cannot detect unknown key names or secret material hidden in values. Rejected values and both JSON
payloads are forbidden from logs and error messages.

Secret references are the required credential mechanism. Their values are nonblank opaque
URI-style reference strings. The control plane checks structure but does not allowlist providers,
validate provider-owned syntax, verify existence, or resolve values. Resolved secrets are
forbidden from persistence, logs, errors, responses, events, snapshots, and AI/MCP context. The
future runner alone resolves scoped references immediately for an execution.

### REST and errors

REST uses `/api/v1/projects/{projectId}/environments` for create, list, read, PUT, status PATCH,
default PATCH, and delete. Lists support paging, sorting, and status/type/default filtering.
Validation is 400, scoped absence is 404, and uniqueness, default-invariant, referenced-delete,
and optimistic-lock conflicts are 409. Successful create/read/update/delete use 201/200/204.

PUT, status PATCH, default PATCH, and DELETE require `If-Match` containing exactly one quoted
nonnegative expected Environment version, for example `If-Match: "3"`. Missing or malformed
headers return 400. A current-version mismatch or JPA optimistic-lock failure returns 409. POST and
GET do not require the header. The client supplies a concurrency precondition; it does not assign
the persisted version. Successful mutation responses contain the new server-controlled version,
except DELETE, which returns 204 without a body after checking the version.

### Deletion and reproducibility

Physical deletion is permitted only when no Execution references the Environment. Application
logic translates a restrictive-FK conflict to 409; PostgreSQL remains authoritative. Referenced
Environments are archived instead. The existing `execution.environment_id` relationship and
`ON DELETE RESTRICT` behavior are preserved.

Mutable Environment rows alone are insufficient for permanent reproducibility. Future execution
design must capture immutable non-secret Environment context required to interpret a run and must
never snapshot resolved secrets.

### Optimistic locking

Environment gains a nonnegative `version` mapped with JPA `@Version`. Concurrent stale writes
produce a stable 409 response. The version is server-controlled and exposed in responses for
observability. Mutation clients provide the expected version only through `If-Match`; create and
update payloads do not assign it.

## Alternatives Considered

### Create a second environment table

Rejected because it would create competing identities and configuration sources while existing
Executions already reference the established aggregate.

### Store configuration and credentials in one JSON object

Rejected because it makes accidental secret persistence, response exposure, logging, and snapshot
copying much more likely. Explicit separate fields make the trust boundary reviewable.

### Store encrypted secret values in the control-plane database

Rejected because encryption does not remove custody, rotation, audit, access-control, and leakage
risks. Automation Studio stores provider references and delegates resolution to the runner.

### Model each secret reference as a relational child row

Deferred because AS-017 needs opaque named references without provider resolution, independent
lifecycle, or cross-Environment sharing. JSONB is sufficient for the current aggregate boundary.

### Enforce the default only in service code

Rejected because concurrent requests, future adapters, or defects could create two defaults. The
partial unique index provides an authoritative PostgreSQL invariant.

### Lock the current default Environment only

Rejected because a Project with no default has no row to lock and concurrent first-default writes
could race. Locking the Project provides a stable parent serialization point.

### Automatically promote another Environment on clear/delete/archive

Rejected because implicit selection could target the wrong system, particularly PRODUCTION.
Zero defaults is an intentional valid state.

### Hard-delete referenced Environments or cascade Executions

Rejected because it would destroy or corrupt execution history. Restrictive deletion and archival
preserve historical relationships.

### Provider-specific secret-reference validation

Deferred because provider integration is outside AS-017 and an allowlist would prematurely couple
the control plane to secret-manager adapters.

## Consequences

### Positive

- Existing identities and Execution history remain intact.
- Project scope and database uniqueness prevent cross-Project leakage and duplicate defaults.
- Configuration is flexible without coupling the core to engines.
- The secret boundary is explicit and resolution stays in the execution plane.
- Optimistic and pessimistic controls address different concurrency risks.
- Archival provides a safe retention path.

### Trade-offs

- Project-row locking serializes default changes within one Project.
- JSONB flexibility requires application validation and future provider/engine schemas.
- Stored secret locators may themselves be sensitive metadata and need later authorization policy.
- Existing Environment types are deterministically backfilled to TEST and may be corrected later.
- Direct Spring `Page` serialization carries the existing response-stability limitation.

## Security Considerations

- Require credentials to use URI-style `secretReferences`; configuration is contractually non-secret.
- Recursively screen the documented secret-shaped keys, while recognizing this cannot prove that
  arbitrary configuration contains no secret values.
- Never log either JSON object or echo rejected secret-shaped values in validation errors.
- Never resolve secrets in the control plane, API, AI features, or MCP adapters.
- Avoid base URLs containing user-info and keep errors free of database/constraint details.
- Future authorization must enforce the same Project-scoped lookup for reads and writes.
- Secret locators returned by APIs require the same protection as other sensitive configuration.

## Implementation and Compatibility Notes

AS-017B backfills every existing Environment type to TEST because no authoritative persisted
mapping exists. This is a compatibility classification, not an assertion about the actual target;
users may correct it through the delivered API. Migration tests must prove upgrade compatibility,
the TEST backfill, JSON object checks, statuses, version constraints, the ACTIVE-default check,
the partial index, and restrictive Execution deletion.

AS-017C through AS-017F follow the staged plan in the SRS. AS-017G records the actual delivered
outcome. This proposed ADR documents the design under review; it does not claim production code,
migration, API, or tests are implemented.

## Acceptance Criteria

- The existing Environment aggregate is evolved rather than duplicated.
- Project isolation and scoped uniqueness are enforced at every access path.
- Zero-or-one ACTIVE default is transactionally and database enforced.
- Configuration is contractually non-secret, recursively screens documented secret-shaped keys,
  and does not claim that screening proves arbitrary JSON is secret-free.
- Secret resolution remains a future scoped runner responsibility.
- Lifecycle, deletion conflict, required mutation `If-Match`, optimistic conflict, REST, and
  validation contracts are explicit.
- Existing IDs and restrictive Execution history relationships survive migration.
- AS-017B through AS-017G deliver and reconcile the decision in stages.
