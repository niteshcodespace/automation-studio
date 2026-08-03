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
AS-025G Real OrangeHRM Runtime Validation - next active phase, not started
AS-025H Production Readiness and Final Documentation - blocked by AS-025G
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
