# AS-024 - Implementation Plan

## Delivery Principles

- architecture and contracts precede browser implementation;
- each story is review-gated and independently testable;
- provider-neutral contracts remain free of Playwright and filesystem types;
- browser binaries are pre-provisioned, never downloaded during execution;
- no story introduces Maven, Gradle, npm, shell, or arbitrary Java execution; and
- no incomplete story is committed or pushed.

## Story Sequence

### AS-024A - Requirements, Architecture, and ADR

Finalize requirements, ADR-014, threat boundaries, manifest execution model, and this plan.
Documentation only.

### AS-024B - Playwright Engine Configuration Contracts

Add the exact dependency/descriptor, immutable configuration, operator properties, strict parsing,
timeout/viewport bounds, and immutable runtime metrics contract. Metrics remain transient engine
telemetry carried by an internal runtime result, with no provider-neutral result change,
persistence, or API.

### AS-024C - Secure Versioned Manifest Contract and Loader

Add explicit schema `"1.0"`, immutable actions, deterministic version negotiation, secure suite
reference resolution, containment/link defenses, structural limits, and compatibility tests.

### AS-024D - Playwright Runtime Boundary and Chromium Adapter

Add the internal runtime port, preinstalled-browser validation, headless Chromium adapter,
one-context/one-page lifecycle, bounded timeouts, startup-duration measurement, and cleanup
precedence. Shape the runtime around execution-scoped resources so future pages, contexts, and
bounded parallelism do not require redesign.

### AS-024E - Pluggable Action and Assertion Execution

Add:

- `PlaywrightActionExecutor`;
- immutable duplicate-safe action registry;
- `SelectorResolver`;
- navigate, click, fill, assert-visible, assert-text, and assert-url executors;
- deterministic action ordering;
- per-action success/failure metric accumulation;
- same-origin navigation enforcement; and
- non-secret bounded variable interpolation.

Executors use an internal runtime façade rather than concrete Playwright page types. No central
switch/case dispatcher or page-object framework.

### AS-024F - ExecutionEngine Integration

Add `PlaywrightExecutionEngine`, existing engine-registry integration, AS-023 workspace access,
manifest/runtime composition, immutable result mapping, metrics finalization, valid
SUCCEEDED/FAILED/CANCELLED handling, and deterministic resource cleanup. Workspace release remains
outside the engine.

### AS-024G - Real-Browser Integration and Security Hardening

Verify successful and failed scenarios, timeout, missing browser, invalid manifest versions,
unknown/duplicate actions, selector limits, path/link escape, same-origin policy, parallel
independent executions, context isolation, metrics consistency, and cleanup using a provisioned
real Chromium.

### AS-024H - Production Readiness and Final Documentation

Complete threat review, dependency/browser provisioning guidance, supported-platform verification,
focused/full Maven gates, operational failure guidance, architecture diagrams, and documentation
reconciliation.

## Implementation Order

```text
AS-024A -> AS-024B -> AS-024C -> AS-024D
        -> AS-024E -> AS-024F -> AS-024G -> AS-024H
```

The approved A-H decomposition is unchanged.

## Review Gates

Every story must confirm:

- only story-scoped changes;
- immutable and sanitized public contracts;
- no forbidden process or persistence behavior;
- focused tests and `git diff --check`;
- full Maven verification when production behavior changes;
- synchronized documentation; and
- no commit or push before explicit review approval.
