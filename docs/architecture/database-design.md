# Database Design

## AS-023 Source Identity Direction

ADR-013 approves the following logical persistence ownership for detailed design in AS-023B:

- Project owns source type and sanitized repository-level identity.
- Automation Suite owns an optional bounded repository-relative source location.
- Execution snapshots source type, sanitized repository identity, exact immutable commit, and the
  resolved optional relative location at admission.

The Execution snapshot is unchanged by later Project or Suite updates. AS-023B will select the
smallest PostgreSQL representation consistent with existing JSONB snapshots, tenancy, validation,
and migration conventions.

Runner workspace paths, temporary directories, credentials, tokens, resolved secrets, Git command
lines/output, and runner-local diagnostics are explicitly non-durable. AS-023A introduces no
schema or Flyway migration.
