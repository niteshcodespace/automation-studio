# Sequence Diagrams

## Execution Lifecycle

An execution is asynchronous. The API accepts a validated request, persists an immutable execution snapshot, and returns an execution identifier. Test work is performed by the dedicated runner, never by an HTTP request thread.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Web as Studio Web
    participant API as Studio API
    participant DB as PostgreSQL
    participant Queue as Job Claiming and Outbox
    participant Runner as Execution Runner
    participant Engine as Engine Plugin
    participant SUT as System Under Test
    participant Store as Artifact Store

    User->>Web: Start suite execution
    Web->>API: Submit execution request
    API->>API: Authenticate, authorize, and validate
    API->>DB: Persist QUEUED execution snapshot and outbox event
    API-->>Web: Accepted with execution identifier
    DB-->>Queue: Publish ExecutionQueued
    Runner->>Queue: Claim command
    Runner->>DB: Acquire lease and create attempt
    Runner->>DB: Publish ExecutionStarted
    Runner->>Runner: Prepare workspace, source, and scoped secrets
    Runner->>Engine: Execute normalized request
    Engine->>SUT: Run automation
    Engine-->>Runner: Stream normalized events and results
    Runner->>Store: Upload evidence
    Runner->>DB: Register ArtifactCreated and terminal result
    Runner->>DB: Publish ExecutionCompleted
    Web->>API: Read execution summary
    API-->>Web: Status, results, and authorized artifact links
```

## Execution State Model

```mermaid
stateDiagram-v2
    [*] --> QUEUED: accepted
    QUEUED --> CLAIMED: runner obtains lease
    CLAIMED --> PREPARING: workspace initialization
    PREPARING --> RUNNING: engine starts
    RUNNING --> COLLECTING: engine completes
    COLLECTING --> SUCCEEDED: results finalized
    COLLECTING --> FAILED: negative test result finalized

    QUEUED --> CANCELLED: cancelled before claim
    CLAIMED --> CANCELLING: cancellation requested
    PREPARING --> CANCELLING: cancellation requested
    RUNNING --> CANCELLING: cancellation requested
    CANCELLING --> CANCELLED: workload stopped

    CLAIMED --> TIMED_OUT: lease or startup timeout
    PREPARING --> TIMED_OUT: preparation timeout
    RUNNING --> TIMED_OUT: execution timeout
    CLAIMED --> INFRASTRUCTURE_ERROR: runner or dependency failure
    PREPARING --> INFRASTRUCTURE_ERROR: preparation failure
    RUNNING --> INFRASTRUCTURE_ERROR: runner or engine failure
```

Terminal states are `SUCCEEDED`, `FAILED`, `CANCELLED`, `TIMED_OUT`, and `INFRASTRUCTURE_ERROR`. `FAILED` means automation completed with a negative test outcome. `INFRASTRUCTURE_ERROR` means the platform could not reliably determine the intended test outcome.

## Advisory AI Failure Analysis

AI analysis is initiated after an authorized request. It consumes evidence and produces a recommendation without changing execution status or result data.

```mermaid
sequenceDiagram
    autonumber
    actor Engineer
    participant Web as Studio Web or MCP Client
    participant API as Studio API
    participant DB as PostgreSQL
    participant Outbox as Event Transport
    participant AI as AI Orchestration Service
    participant Context as Context Builder
    participant Safety as AI Audit and Safety Controls
    participant Prompt as Prompt Template Registry
    participant Gateway as LLM Provider Gateway
    participant LLM as Approved LLM Provider

    Engineer->>Web: Request failure analysis
    Web->>API: Submit analysis request
    API->>API: Authorize and validate policy
    API->>DB: Persist AnalysisRequested and audit record
    API->>Outbox: Publish AnalysisRequested
    Outbox-->>AI: Deliver analysis work
    AI->>Context: Build minimum necessary evidence context
    Context->>DB: Read execution, results, and artifact references
    Context-->>AI: Immutable context snapshot
    AI->>Safety: Redact, classify, and validate request
    Safety->>Prompt: Select approved prompt version
    Safety->>Gateway: Invoke approved model with redacted context
    Gateway->>LLM: Provider request
    LLM-->>Gateway: Generated response
    Gateway-->>Safety: Normalized response and usage data
    Safety->>Safety: Validate output, evidence, and uncertainty
    Safety->>DB: Store audited AI recommendation
    Safety->>Outbox: Publish AnalysisCompleted
    API-->>Web: Recommendation available
```

The analysis path is optional. Provider unavailability, rejected content, or invalid model output results in an analysis failure record and never changes the associated execution outcome.

## MCP Mutation Approval

```mermaid
sequenceDiagram
    autonumber
    participant Client as MCP Client or Agent
    participant MCP as Automation Studio MCP Server
    participant Auth as Authorization Service
    actor Human as Authorized Human
    participant API as Studio API
    participant Audit as Audit Service

    Client->>MCP: Request mutating tool operation
    MCP->>Auth: Authenticate and authorize actor
    Auth-->>MCP: Operation allowed subject to approval
    MCP->>Human: Request approval bound to operation and parameters
    Human-->>MCP: Approve or reject
    alt Approved
        MCP->>API: Invoke authorized application command
        API->>Audit: Record actor, client, operation, and result
        API-->>MCP: Command result
        MCP-->>Client: Result
    else Rejected or expired
        MCP->>Audit: Record denied operation
        MCP-->>Client: Approval required or denied
    end
```

Read-only MCP tools and resources still require normal authentication, authorization, and audit. Secret values are not available through MCP.
