# Product Roadmap

## Phase 1

- Project foundation
- Environment management
- Runner workspace and source preparation
- Playwright Java engine
- OrangeHRM smoke test execution

Current delivery order:

```text
AS-022 Runner Execution Orchestrator (complete)
    -> AS-023 Runner Workspace and Source Preparation (complete)
    -> AS-024 Playwright Java Execution Engine (complete and merged)
    -> AS-025 First Business Automation Execution
```

### AS-025 - First Business Automation Execution

AS-025 proves the first complete business execution through the existing platform pipeline. The
initial OrangeHRM login smoke scenario is scenario and environment data, not a target-specific
engine or orchestration path.

```text
AS-025A Requirements and Architecture - documentation complete, pending final review
AS-025B Secret Resolution Boundary - blocked
AS-025C Manifest and Sensitive Fill Composition - blocked
AS-025D Orchestrator Integration - blocked
AS-025E OrangeHRM Scenario Source - blocked
AS-025F Complete Controlled Pipeline Verification - blocked
AS-025G Real OrangeHRM Runtime Validation - blocked
AS-025H Production Readiness and Final Documentation - blocked
```

The approved sequence preserves immutable execution snapshots, keeps resolved values outside
`ExecutionContext` and normal variables, adds secret use only through an explicit versioned
sensitive sink, reuses scheduling/claim/workspace/engine/result/cleanup ownership, and keeps
ordinary verification isolated from external targets and operator secrets.

## Phase 2

- Selenium Java engine
- Dashboard
- Reporting

## Phase 3

- REST Assured
- Karate
- Database validation

## Phase 4

- AI-assisted failure analysis
- Mobile and performance testing
