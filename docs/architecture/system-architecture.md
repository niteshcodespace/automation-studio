# Automation Studio System Architecture

## Purpose

Automation Studio is an AI-Native Quality Engineering Platform for managing automation projects, environments, execution, evidence, reports, and AI-assisted quality-engineering workflows.

AI-native means the platform has explicit AI domain, security, audit, and integration boundaries. It does not make the core automation path dependent on an AI provider. In v0.1, a user can run an OrangeHRM Playwright smoke suite, collect evidence, and review the result without AI being enabled.

## Design Principles

- Separate the authoritative control plane from isolated execution workloads.
- Use contract-first, versioned extensions for engines and future integrations.
- Prefer asynchronous, durable workflows for long-running operations.
- Preserve immutable execution facts and make historical runs reproducible.
- Treat AI output as evidence-grounded advice, never as an authoritative fact.
- Apply least privilege, explicit authorization, and secret references by default.
- Start with deployable simplicity while preserving evolution-ready boundaries.

## Quality Attributes

| Attribute | Architectural response |
|---|---|
| Extensibility | Versioned engine, artifact, work-transport, LLM-provider, and MCP interfaces |
| Reliability | Durable execution state, transactional outbox, guarded leases, retries, and immutable history |
| Security | Scoped identities, secret references, runner isolation, redaction, audit, and approval gates |
| Maintainability | Domain-oriented modules and explicit dependency boundaries |
| Observability | Correlation identifiers, execution events, structured logs, metrics, and audit records |
| Portability | Separate runtime boundaries and replaceable infrastructure adapters |

## Non Goals

- Requiring AI services, MCP, Kubernetes, an external broker, multi-tenancy, or high availability for v0.1.
- Replacing human quality-engineering judgment with automated AI decisions.
- Allowing AI or engine plugins to modify authoritative execution outcomes or history.
- Defining the detailed database schema or REST API contract; those belong to AS-008 and AS-009.
- Prescribing a single cloud provider, model provider, message broker, or artifact-store implementation.

## Architecture Constraints

- The initial control plane remains a Spring Boot modular monolith.
- Automation runs through a dedicated execution runner, not an HTTP request thread.
- Engines implement the shared, versioned contract defined by ADR-001.
- PostgreSQL is the initial authoritative metadata store and supports initial job claiming and outbox delivery.
- Artifact bytes remain behind an external storage abstraction; a local filesystem adapter is acceptable in v0.1.
- Secrets are stored and exchanged as references, not persisted values.
- AI and MCP capabilities use the same authorization, audit, and safety boundaries as the control plane.

## Architecture Goals

- Keep control-plane responsibilities separate from automation execution.
- Support independent automation technologies through a versioned engine contract.
- Provide durable, asynchronous, observable execution.
- Preserve immutable, reproducible execution history and evidence metadata.
- Keep secrets as references and resolve them only for a scoped execution.
- Make AI advice evidence-grounded, auditable, and non-authoritative.
- Provide an evolution path for MCP clients and enterprise deployment.

## Logical Planes

| Plane | Responsibility | v0.1 implementation |
|---|---|---|
| Control plane | Projects, environments, suites, scheduling, results, reports, access control, audit, and event publication | Spring Boot modular monolith |
| Execution plane | Work claiming, workspace preparation, secret resolution, engine invocation, cancellation, evidence, and cleanup | Dedicated Java runner with Playwright Java engine |
| AI capability plane | Context, prompts, provider access, analysis, recommendations, and safety controls | Evolution-ready interfaces; no mandatory provider |
| Integration plane | Web, REST, CI, and MCP access to governed services | Web and REST in v0.1; MCP boundary reserved for later |

The control plane owns authoritative business state. The execution plane may report results, but it does not change project configuration or authorization rules. The AI capability plane produces recommendations only; it cannot alter authoritative execution outcomes or history.

## System Context

```mermaid
C4Context
    title Automation Studio - System Context

    Person(engineer, "Quality Engineer", "Configures projects, environments, suites, and executions")
    Person(admin, "Platform Administrator", "Manages access, engines, AI policies, and operations")
    Person(viewer, "Stakeholder", "Reviews execution outcomes and reports")
    Person_Ext(mcpClient, "MCP Client", "Authorized assistant or agent")

    System(studio, "Automation Studio", "AI-Native Quality Engineering Platform")

    System_Ext(idp, "Identity Provider", "OIDC identity and role claims")
    System_Ext(scm, "Source Control System", "Versioned automation source")
    System_Ext(sut, "System Under Test", "Application, API, database, or other test target")
    System_Ext(secrets, "Secret Manager", "Scoped environment and integration secrets")
    System_Ext(llm, "LLM Provider", "Hosted, private, or self-hosted model")

    Rel(engineer, studio, "Manages quality workflows", "HTTPS")
    Rel(admin, studio, "Administers", "HTTPS")
    Rel(viewer, studio, "Reviews", "HTTPS")
    Rel(mcpClient, studio, "Uses governed tools and resources", "MCP")
    Rel(studio, idp, "Authenticates through", "OIDC/OAuth 2.0")
    Rel(studio, scm, "Retrieves approved revisions", "HTTPS/SSH")
    Rel(studio, secrets, "Resolves scoped secret references", "TLS")
    Rel(studio, sut, "Runs automation against", "Target-specific protocols")
    Rel(studio, llm, "Requests advisory analysis", "TLS")
```

## Logical Containers

```mermaid
C4Container
    title Automation Studio - Logical Containers

    Person(user, "Studio User", "Engineer, administrator, or stakeholder")
    Person_Ext(mcpClient, "MCP Client", "Authorized assistant or agent")
    System_Ext(idp, "Identity Provider", "OIDC identity and claims")
    System_Ext(scm, "Source Control", "Versioned automation source")
    System_Ext(sut, "System Under Test", "Automation target")
    System_Ext(secretStore, "Secret Manager", "Scoped credentials")
    System_Ext(llm, "LLM Providers", "Approved hosted, private, or local models")

    System_Boundary(studio, "Automation Studio") {
        Container(web, "Studio Web", "Next.js", "User interface")
        Container(api, "Studio API", "Java / Spring Boot", "Modular control plane and authoritative application services")
        Container(mcp, "Automation Studio MCP Server", "MCP adapter", "Governed tools and resources")
        Container(ai, "AI Orchestration Service", "Logical service", "Advisory AI workflows, safety, and provenance")
        Container(runner, "Execution Runner", "Java", "Claims work and coordinates execution")
        Container(engine, "Engine Plugins", "Versioned modules", "Playwright initially; additional engines later")
        ContainerDb(db, "Metadata Store", "PostgreSQL", "State, history, audit, prompts, recommendations, and outbox")
        Container(queue, "Work and Event Transport", "PostgreSQL adapter initially", "Durable commands and events")
        ContainerDb(artifacts, "Artifact Store", "Local filesystem initially", "Logs, screenshots, traces, videos, and reports")
    }

    Rel(user, web, "Uses", "HTTPS")
    Rel(web, api, "Calls", "REST/HTTPS")
    Rel(mcpClient, mcp, "Invokes", "MCP")
    Rel(mcp, api, "Uses authorized application services", "Internal API")
    Rel(api, idp, "Validates identity", "OIDC/JWT")
    Rel(api, db, "Reads and writes", "JDBC/TLS")
    Rel(api, queue, "Publishes committed commands and events", "Versioned envelopes")
    Rel(runner, queue, "Claims work and emits progress", "Versioned envelopes")
    Rel(runner, db, "Persists guarded state and heartbeats", "JDBC/TLS")
    Rel(runner, engine, "Invokes", "Engine contract")
    Rel(runner, scm, "Retrieves approved revision", "HTTPS/SSH")
    Rel(runner, secretStore, "Resolves execution secrets", "TLS")
    Rel(engine, sut, "Executes tests", "Engine-specific protocol")
    Rel(runner, artifacts, "Stores evidence", "Artifact port")
    Rel(api, ai, "Requests advisory workflows", "Application interface")
    Rel(ai, queue, "Consumes analysis work and events", "Versioned envelopes")
    Rel(ai, db, "Stores recommendations and audit", "JDBC/TLS")
    Rel(ai, artifacts, "Reads authorized evidence", "Artifact port")
    Rel(ai, llm, "Invokes approved models", "TLS")
```

The MCP Server and AI Orchestration Service are logical boundaries. They may be implemented as modules deployed with the API initially, then separated when traffic, security isolation, or operational requirements justify it.

## Core Domain Concepts

The authoritative domain includes Project, Environment, Test Suite, Test Case, Engine, Engine Version, Execution, Execution Attempt, Test Result, Step Result, Artifact, Execution Event, and Audit Event.

An execution stores immutable snapshots of the selected suite revision, non-secret environment configuration, engine version, and relevant runtime configuration. Secret values are never included in these snapshots.

The AI domain adds Analysis Request, Context Snapshot, Prompt Template, Model Invocation, AI Recommendation, Generation Proposal, Approval Decision, and AI Safety Event. These records reference authoritative facts; they do not replace or mutate them.

## Event Architecture

Events decouple execution, reporting, AI analysis, and future integrations. The initial implementation uses a PostgreSQL transactional outbox and durable job claiming. An external broker is an optional later adapter.

| Event | Meaning |
|---|---|
| `ProjectCreated` | A project was created. |
| `EnvironmentConfigured` | An environment was created or materially updated. |
| `ExecutionQueued` | An execution was accepted for processing. |
| `ExecutionStarted` | A runner began an execution attempt. |
| `ExecutionCompleted` | An execution reached a terminal state. |
| `ArtifactCreated` | Evidence was stored and registered. |
| `AnalysisRequested` | An authorized AI analysis was requested. |
| `AnalysisCompleted` | Analysis completed or failed. |
| `EngineRegistered` | An engine version became available. |

Events include an event identifier, schema version, aggregate identifier, project identifier where applicable, correlation identifier, causation identifier, actor, occurrence time, and non-secret payload. Consumers are idempotent because delivery is at least once.

## AI and MCP Safety Boundaries

- AI output is advisory and must not change authoritative test outcomes.
- Sensitive data is redacted before model invocation.
- Every invocation records the model, provider, prompt version, input references, output, and safety decisions.
- Recommendations include evidence references and uncertainty.
- Generated tests and fixes remain proposals until an authorized human approves them.
- MCP tools invoke the same application services and authorization rules as the web and REST interfaces.
- Mutating or destructive MCP actions, including agent-started executions, cancellation, and proposal approval, require explicit human approval by default.
- Secret values are never exposed through MCP tools or resources.

## v0.1 Scope and Evolution

v0.1 uses a Spring Boot modular monolith, Next.js web application, PostgreSQL, a dedicated Java runner, a Playwright Java engine, PostgreSQL-based job claiming/outbox, and a local filesystem artifact adapter. It does not require AI, MCP, Kubernetes, Kafka, RabbitMQ, multi-tenancy, or high availability.

Future installations may add S3-compatible artifact storage, external secret management, separate AI services, MCP deployment, external brokers, isolated execution containers, specialized runner pools, Kubernetes, high availability, and multi-tenancy without changing the core domain boundaries.
