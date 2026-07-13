# ADR-001: Modular Automation Engine Architecture

## Status

Accepted

## Context

Automation Studio is an AI-Native Quality Engineering Platform that must support multiple automation technologies without coupling project management, execution history, reporting, or AI-assisted analysis to a specific framework.

The initial release needs Playwright Java for an OrangeHRM smoke suite. The product roadmap also anticipates Selenium, REST Assured, Karate, database validation, mobile, and performance testing. These technologies have different runtime requirements, test models, evidence types, and native result formats.

The platform must preserve a practical v0.1 implementation while creating a stable extension mechanism for future engines. AI capabilities must consume normalized execution facts and evidence rather than depend on engine-specific internals.

## Decision

Automation technologies will be implemented as independent engine modules that implement a shared, versioned engine contract.

The control plane remains independent of engine implementations. It owns projects, environments, suite selection, authorization, execution state, immutable history, artifact metadata, reporting, and audit. A dedicated execution runner owns workspace preparation, scoped secret resolution, work leases, timeouts, cancellation, plugin invocation, evidence collection, and cleanup.

An engine module is responsible only for technology-specific validation, discovery where supported, execution, and translation of native output into normalized events, results, and artifact descriptions.

The contract requires engines to declare:

- Stable engine identifier and implementation version.
- Supported contract versions.
- Capabilities and runtime requirements.
- Configuration schema and secret markers.
- Integrity metadata and loadable entry point.

The contract supports capability description, validation, optional discovery, execution, cancellation, health reporting, and resource cleanup. Requests carry an execution identifier, attempt identifier, workspace, source revision, test selection, non-secret configuration, scoped secret material, artifact path, and timeouts. Engines emit normalized lifecycle, test-result, step-result, log, and artifact events.

Engine plugins must not write Automation Studio database tables directly, make authorization decisions, select work independently, resolve arbitrary platform secrets, or alter authoritative execution outcomes outside the runner-mediated state model.

Playwright Java is the first engine implementation. It runs behind the dedicated runner boundary rather than inside a control-plane HTTP request.

## Consequences

### Positive

- The platform can add engines without changing core project or execution behavior.
- Results and artifacts can be normalized for reporting and AI-assisted analysis.
- Framework-specific dependencies are isolated from the control plane.
- Engine runtime failures are isolated from API request processing.
- Contract tests can verify built-in and community engine compatibility.
- Engine capability metadata enables future catalog, scheduling, and runner-pool selection.

### Trade-offs

- A normalized contract cannot expose every native engine feature directly.
- Engine authors must implement mapping, validation, and contract tests.
- Contract versioning and compatibility policy require active maintenance.
- Initial plugin loading may be in-process within the runner; untrusted or resource-intensive engines will later require stronger process or container isolation.

## v0.1 Application

v0.1 provides one Playwright Java engine, one dedicated runner, PostgreSQL-based job claiming and outbox delivery, and a local filesystem artifact adapter. It does not require additional engines, external brokers, Kubernetes, AI services, or MCP deployment.

AI and MCP are evolution-ready consumers of normalized execution facts. They do not modify this engine contract's ownership boundaries and are not required to run an execution.

## Follow-up Decisions

Separate ADRs will define asynchronous execution and outbox delivery, artifact storage, AI safety and provider integration, MCP authorization, and the detailed contract compatibility policy when those capabilities are implemented.


## Alternatives Considered

### Option A — Embed Playwright directly into Spring Boot

Rejected.

Reason:

Tight coupling between API and automation runtime.
Would make future engines difficult to support.

### Option B — Independent Engine Plugins (Selected)

Selected because it:

- isolates runtime dependencies
- supports multiple engines
- enables contract testing
- simplifies future expansion