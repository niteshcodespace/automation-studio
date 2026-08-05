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
AS-025A Requirements and Architecture - completed, reviewed, approved, and committed (09bc9d12208c3a6c0092ef524b7b7803faf7153d)
AS-025B Secret Resolution Boundary - completed, reviewed, approved, and committed (e53423789e4c2163f9a7f85006b790e27954960b)
AS-025C Manifest and Sensitive Fill Composition - completed, reviewed, approved, and committed (9f7765a9ab7afb47d18ce2211e0b22b544d573b5)
AS-025D Orchestrator Integration - completed, reviewed, approved, and committed (05d0229d870ca2cec2bf63605cf4fc10d7b5a058)
AS-025E OrangeHRM Scenario Source - completed, reviewed, approved, and committed (65b9f5ea2a7118751c4fdcb89166b7e08fc30d05)
AS-025F Complete Controlled Pipeline Verification - completed, reviewed, approved, and committed (654bdcc025ff738738f9346a2896adab59103650)
AS-025G Real OrangeHRM Runtime Validation - completed, reviewed, approved, and committed (d82593ec520a7416c49f1315e2881665a5f96647)
AS-025H-A Production Readiness Runbook - completed, reviewed, approved, and committed (195abf95f31eb2564abdbb7f7ac2e18ed7959f67)
AS-025H-B Documentation Reconciliation - completed, reviewed, approved, and committed (67055700ecd8eb8da48c8e484209d5808fb45f38); no runtime functionality
AS-025H-C Final Verification and Feature-Level Review - completed, reviewed, and approved
AS-025H Production Readiness and Final Documentation - completed, reviewed, and approved
AS-025 First Business Automation Execution - complete
AS-026A Requirements and Architecture Reconciliation - completed and independently reviewed; awaiting commit approval
AS-026B Canonical Engine Identity and Descriptor Contract - implemented and committed (8589afa); merge state not separately evidenced
AS-026C and later phases - not started; blocked pending AS-026A acceptance
```

The approved sequence preserves immutable execution snapshots, keeps resolved values outside
`ExecutionContext` and normal variables, adds secret use only through an explicit versioned
sensitive sink, reuses scheduling/claim/workspace/engine/result/cleanup ownership, and keeps
ordinary verification isolated from external targets and operator secrets.

AS-025G's manual portfolio/learning qualification may use only the official canonical OrangeHRM
public-demo origin `https://opensource-demo.orangehrmlive.com`, only for the existing
non-destructive login-and-dashboard smoke scenario. It remains prohibited in CI and recurring
execution; public credentials remain operator-injected secrets, and external demo instability is
reported separately from platform defects.

AS-025H-A recorded the production-readiness runbook, and AS-025H-B reconciled supporting and
governing documentation only. AS-025H-C completed focused and full inert verification and the final
feature-level architecture, security, operations, and deferred-scope review. The accepted AS-025G
qualification evidence remains authoritative. AS-025 is complete. AS-026B implementation exists at
`8589afa`; AS-026A has reconciled its missing governing documents. AS-026C remains blocked until
the AS-026A repository checkpoint is explicitly approved and committed.

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
