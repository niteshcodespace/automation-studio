# Database Design

## AS-023 Source Identity

ADR-013 approves the following logical persistence ownership for detailed design in AS-023B:

- Project owns source type and sanitized repository-level identity.
- Automation Suite owns an optional bounded repository-relative source location.
- Execution snapshots source type, sanitized repository identity, exact immutable commit, and the
  resolved optional relative location at admission.

V15 implements:

| Table | Column | Type | Nullability |
|---|---|---|---|
| `project` | `source_type` | `VARCHAR(30)` | Nullable as part of all-or-none configuration |
| `project` | `source_repository` | `VARCHAR(1000)` | Nullable as part of all-or-none configuration |
| `project` | `source_revision` | `VARCHAR(40)` | Nullable as part of all-or-none configuration |
| `test_suite` | `source_location` | `VARCHAR(500)` | Nullable |
| `execution` | `source_snapshot` | `JSONB` | Nullable whole snapshot |

The Execution snapshot is unchanged by later Project or Suite updates. The existing immutable
snapshot trigger now includes `source_snapshot`. Structural constraints enforce Project
all-or-none state, `GIT_HTTPS`, lowercase 40-hex revisions, portable Suite paths, and JSON object
shape. Application validation owns URI and complete semantic validation.

Runner workspace paths, temporary directories, credentials, tokens, resolved secrets, Git command
lines/output, and runner-local diagnostics are explicitly non-durable. Historical V14 rows remain
null and readable; no source identity is invented.
