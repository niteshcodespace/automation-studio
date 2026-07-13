# Architecture Summary

> Last updated: 2026-07-13

This is a navigation summary, not a replacement for the approved architecture documents.

## Runtime Boundaries

- **Control plane:** A Spring Boot modular monolith owns projects, environments, suites, authorization, execution admission and state, immutable history, artifact metadata, reports, audit, and event publication.
- **Execution plane:** A separate runtime boundary claims work, prepares bounded workspaces, retrieves approved source revisions, resolves scoped secrets, invokes engines, enforces cancellation/timeouts, and collects results and evidence.
- **Runner:** The dedicated Java runner coordinates an execution attempt, maintains its lease/heartbeat, invokes a compatible engine, and reports guarded state transitions. Automation does not execute on an API request thread.
- **Engine contract:** Engines are independent modules behind a shared, versioned contract covering capabilities, validation, optional discovery, execution, cancellation, health, normalized events/results, and cleanup. Engines cannot bypass platform authorization or write control-plane tables directly.
- **Evidence and artifacts:** PostgreSQL holds authoritative state and artifact metadata; artifact bytes use an artifact-storage port, initially backed by the local filesystem. Results and evidence remain durable, attributable, and auditable.
- **Initial queue:** v0.1 uses PostgreSQL durable job claiming and a transactional outbox. An external broker is a future adapter, not an initial prerequisite.
- **AI boundary:** Optional AI modules consume authorized, redacted facts and evidence and store audited recommendations separately from authoritative execution results. AI failure cannot change execution state.
- **MCP boundary:** A future MCP adapter must use the same application services, authorization, audit, and approval rules as REST and must never expose secret values.

## Detailed References

- [`System architecture`](../docs/architecture/system-architecture.md)
- [`Module architecture`](../docs/architecture/module-architecture.md)
- [`Execution and AI sequences`](../docs/architecture/sequence-diagrams.md)
- [`Deployment architecture`](../docs/architecture/deployment.md)
- [`ADR-001: Modular Automation Engine Architecture`](../docs/adr/ADR-001-modular-engine-architecture.md)

