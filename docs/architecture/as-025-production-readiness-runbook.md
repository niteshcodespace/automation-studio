# AS-025 Production Readiness Runbook

## Purpose and Delivery Boundary

AS-025 proves the first complete business automation through the existing Automation Studio runner
pipeline. The initial scenario is the repository-owned OrangeHRM login-and-dashboard smoke
scenario, but OrangeHRM remains declarative environment and manifest data rather than a platform
engine, orchestration branch, or lifecycle.

This runbook documents the implementation qualified after AS-025G. It adds no runtime behavior.
The supported feature boundary is:

- one immutable admitted execution using the existing scheduling, claim, lease, and fencing model;
- one repository-owned Playwright manifest using schema `2.0` and the six existing action types;
- secret-backed values only through `fill.secretRef`;
- one operator-provisioned Chromium-compatible executable in headless mode;
- one non-persistent browser context and page per execution;
- same-origin HTTP/HTTPS navigation;
- provider-neutral normalized `PASSED`, `FAILED`, or `ERROR` persistence; and
- deterministic browser, secret-scope, workspace-access, and physical-workspace cleanup.

Schema `1.0` remains compatible and retains its existing non-secret `fill.value` semantics. AS-025
does not add secret interpolation, new actions, retries, artifacts, alternate browsers, persistent
profiles, production target administration, or a browser installer.

## Architecture Summary and Runtime Ownership

The established component owners remain authoritative:

| Concern | Authoritative owner |
|---|---|
| Admission snapshots and business lifecycle | Existing execution-management admission path |
| Scheduling, atomic claim, lease, and fencing | Existing runner scheduling and ownership services |
| Controlled runner composition | `RunnerPipelineCoordinator` |
| Fenced start, terminal transition, and persistence | `RunnerExecutionService` |
| Context construction, preparation, engine invocation, and resource coordination | `ExecutionOrchestrator` |
| Physical workspace preparation and release | Existing workspace preparation boundary |
| Exact source materialization | Existing source materialization boundary |
| Manifest validation, browser execution, and browser cleanup | `PlaywrightExecutionEngine` |
| Provider selection and resolved-value lifetime | Execution-scoped secret scope |
| Scenario meaning | Immutable admitted snapshots and repository manifest |

`RunnerPipelineCoordinator` is the only authoritative controlled execution path. It requests a
fenced start, invokes the provider-neutral `ExecutionOrchestrator` outside lifecycle transactions,
maps the normalized result, and requests one fenced terminal completion.

`RunnerExecutionService` owns locks, ownership validation, state transitions, and durable terminal
persistence. It does not run the browser or resolve secrets. Loss of ownership prevents a stale
terminal write.

`ExecutionOrchestrator` remains provider-neutral. It prepares the workspace, materializes the exact
admitted revision, constructs the immutable execution context, creates the execution-scoped secret
capability, selects and invokes the engine, and coordinates cleanup. It contains no OrangeHRM or
provider-specific decision.

`PlaywrightExecutionEngine` owns manifest validation, workspace access, the browser session,
ordered action execution, provider-neutral result construction, and closing its workspace-access
handle. It does not release the physical workspace or persist an execution status.

The secret scope belongs to exactly one execution. It is created after immutable context
construction and before engine invocation, resolves only requested admitted logical names, and is
closed after engine cleanup and before physical workspace release. Closure is deterministic and
idempotent; resolved handles reject access after close.

The workspace preparation boundary owns the physical workspace. The engine owns only its scoped
access handle. The orchestrator releases the physical workspace after engine and secret-scope
cleanup. No engine or manifest may delete, retain, or expose the runner-local workspace.

## Authoritative Execution Flow

```text
Admission
    -> Scheduling
    -> Atomic claim and lease ownership
    -> Fenced start through RunnerExecutionService
    -> RunnerPipelineCoordinator
    -> ExecutionOrchestrator
    -> Isolated workspace preparation
    -> Exact admitted source materialization
    -> Immutable ExecutionContext construction
    -> Execution-scoped secret-scope creation
    -> Exact engine resolution
    -> Schema 2.0 manifest loading and validation
    -> Playwright runtime and ordered action execution
    -> Lazy resolution at each approved sensitive fill boundary
    -> Provider-neutral normalized result
    -> Browser/session and workspace-access cleanup
    -> Secret-handle and secret-scope cleanup
    -> Physical-workspace release
    -> Orchestrator returns the final normalized outcome
    -> Fenced terminal persistence through RunnerExecutionService
```

All successful assertions map to `PASSED`. A completed business assertion mismatch maps to
`FAILED`. Secret, provider, preparation, source, browser, runtime, infrastructure, or cleanup
failure maps to `ERROR` while the lease remains owned. Accepted cancellation remains a separate
existing lifecycle. Ownership loss is never translated into a terminal result and cannot perform a
stale write.

## Operator Configuration

### Secret provider

The initial adapter is the existing `operator-environment` provider. It is disabled unless the
operator explicitly enables:

```text
automation.runner.secrets.operator-environment.enabled=true
```

The provider reads only the exact environment keys carried by immutable admitted secret-reference
metadata. Provider configuration and injected material are operator-owned and must remain outside
source, manifests, Maven arguments, persisted configuration, execution results, evidence, and
logs.

The OrangeHRM scenario admits exactly these case-sensitive logical bindings:

- `orangehrm.username`
- `orangehrm.password`

Their provider references select operator environment keys, but key details and resolved values
must not be copied into operator reports or qualification evidence.

### Chromium provisioning

Automation Studio never searches for, downloads, or installs a browser. The operator provisions,
patches, attests, and protects a compatible Chromium executable and configures its absolute path:

```text
automation.runner.playwright.executable-path=<absolute operator-provisioned path>
```

The runner identity must be able to execute that file. The configured path is host-sensitive and
must not be committed, embedded in a manifest, returned in results, or logged. Browser product,
build, package source, patch level, and replacement are operator responsibilities. Support applies
only to a runner OS, architecture, Java, Playwright, browser product/build/source, and target
combination that has passed the qualification gate.

### Required qualification inputs

The AS-025G entry point remains manual and inert unless all prerequisites are explicitly present:

- `automation.as025g.enabled=true`;
- `automation.runner.playwright.executable-path` set to an existing absolute executable;
- `automation.runner.secrets.operator-environment.enabled=true`;
- `automation.as025g.target-url` set to the approved canonical origin;
- `automation.as025g.target-classification=NON_PRODUCTION`;
- `automation.as025g.browser-product` set to operator-reviewed metadata;
- `automation.as025g.browser-build` set to operator-reviewed metadata; and
- both approved credential environment entries present in the operator process.

Credential material must be injected separately in the operator shell. It must never be placed in
the Maven command, this runbook, source, test output, or retained evidence. Prerequisite validation
checks presence only; it must not eagerly resolve or inspect a value.

## Secret Handling

Immutable execution snapshots and `ExecutionContext` contain secret references only. Resolved
values never enter normal variables, interpolation, persistence, results, evidence, metrics, or
diagnostics.

Resolution is lazy. A validated schema `2.0` `fill.secretRef` action supplies only its logical name
to the execution-scoped capability. The scope binds that name to the exact immutable admitted
reference and then selects the exact registered provider. There is no fallback to another logical
name, process key, JVM property, file, default account, or similarly named value.

Only `fill.secretRef` is an approved sensitive sink. Secret references in navigation URLs,
selectors, assertions, cookies, headers, scripts, files, or any non-fill action fail validation.
Schema `1.0` never resolves secrets.

Each resolved value is owned by the execution scope, delivered at the latest practical fill
boundary, and released in a `finally`-equivalent path. Scope closure clears mutable backing storage
where possible and makes subsequent access fail. Separate executions have separate scopes,
handles, browser contexts, pages, workspaces, and cleanup lifecycles.

Missing or disabled providers, invalid references, unknown or duplicate names, unavailable, blank,
or oversized values, provider failures, and post-close access fail closed with stable sanitized
classifications. Raw provider or SDK messages are not an approved public diagnostic surface.

## Browser Qualification

The committed AS-025G qualification used Playwright Java `1.61.0` with an operator-provisioned
Google Chrome `150.0.7871.126` executable on Windows 11 amd64 with Java 21.0.5. This evidence
qualifies that recorded combination only; it is not a general compatibility claim for other Chrome
or Chromium builds, operating systems, architectures, images, or Java versions.

Supported execution is headless with one non-persistent context and page. Browser executable
discovery, Playwright browser downloads, installer fallback, persistent profiles, headed mode,
Firefox, and WebKit are not supported.

The real-target test is explicitly opt-in and must remain excluded from ordinary Maven verification.
Default compile, focused tests, and the full Maven suite must not launch a browser, contact the
target, install a browser, or resolve operator secrets.

Requalify before claiming support for a changed runner OS/image, CPU architecture, Java runtime,
Playwright version, browser product/build/source, browser security update with compatibility impact,
or target classification. Requalification remains manual and bounded.

## Target Authorization

For the AS-025G portfolio and learning qualification only, the approved target is exactly the
canonical, pathless HTTPS origin:

```text
https://opensource-demo.orangehrmlive.com
```

It must be classified `NON_PRODUCTION`. Authorization is limited to one bounded execution of the
existing non-destructive login-and-dashboard smoke scenario. The run may navigate, fill the two
approved login fields, submit the login form, and assert the dashboard outcome. It may not create,
modify, or delete an employee, account, role, configuration, password, or business record.

The public demo remains prohibited for CI, scheduled, recurring, retry, load, scanning, probing, or
aggressive repeated execution. Other public targets, all production targets, and non-canonical,
non-HTTPS, path-bearing, or unrecognized target inputs remain unauthorized. Demo unavailability,
rate limiting, reset, or markup change must be classified separately from a platform defect.

## Threat Model and Residual Risks

| Threat or limitation | Implemented control | Residual/operator responsibility |
|---|---|---|
| Operator environment exposure | Explicit provider enablement, exact bounded keys, execution-scoped lazy lookup, no fallback | Protect process creation, environment access, diagnostics, host administration, and runner identity |
| Immutable Java `String` at SDK boundary | Constructed at the latest practical fill call, not retained or emitted, owned handles closed | Java and Playwright can create transient immutable copies that cannot be reliably zeroized; accept and review this v0.1 risk |
| Browser compromise or vulnerable build | Absolute operator-owned executable, non-persistent context, bounded lifecycle, no installer | Attest, patch, replace, and requalify the browser; restrict runner privileges and egress |
| Secret disclosure through output | Secret values prohibited from source, snapshots, context, variables, results, logs, reports, evidence, and diagnostics | Restrict access to internal causes and host diagnostics; treat any suspected disclosure as an incident |
| Secret use in an unintended sink | Schema `2.0` permits `secretRef` only on `fill`; exact logical binding | Review every future sink as a new security and compatibility decision |
| Cross-execution leakage | Independent workspace, browser context/page, secret scope, handles, and cleanup | Enforce runner isolation and host resource boundaries |
| Browser or workspace cleanup failure | Reverse-order cleanup continues; failures prevent false success and are sanitized | Quarantine the runner until processes, profiles, handles, and workspace state are verified |
| Ownership loss | Fenced ownership validation before authoritative transitions | Do not override fencing or manually fabricate terminal state |
| Stale terminal write | `RunnerExecutionService` remains the only fenced persistence authority | Investigate lease loss separately; never bypass the coordinator to force completion |
| External target instability | Manual bounded qualification and normalized platform outcomes | Classify target reset, outage, throttling, or change separately; do not add retries |
| Browser resource exhaustion | One bounded session/context/page and finite timeouts | Apply host CPU, memory, disk, process, and concurrency limits; capacity testing remains deferred |

## Observability and Evidence

Approved operational telemetry uses execution or correlation identity, stable lifecycle phases,
durations, normalized terminal outcomes, and fixed sanitized codes. Qualification evidence is
limited to runner OS and architecture, Java and Playwright versions, browser product/build/source,
target classification, manifest revision, normalized result counts, cleanup result, and reviewed
skips.

Do not emit credential values, account identifiers, provider references or keys, selectors, page
content, cookies, tokens, session data, environment contents, executable or workspace paths, raw
manifest content, browser output, or raw exception messages. Cause retention inside a restricted
internal exception chain is not permission to log or persist it.

## Incident Response

### Qualification failure

1. Stop after the bounded attempt; do not introduce retries.
2. Record only the normalized status, stable sanitized classification, approved environment
   metadata, and cleanup outcome.
3. Separate prerequisite, runner/browser, platform, and public-demo instability classifications.
4. Confirm cleanup before another run is considered.
5. Correct the independently established cause and obtain fresh authorization before requalification.

### Browser compromise or untrusted executable

1. Disable the affected runner from receiving work and revoke its qualification.
2. Stop using the executable and preserve only approved, secret-safe incident metadata.
3. Inspect the host through the operator security process; do not publish raw browser diagnostics.
4. Replace the browser from an approved source, patch the host, and review runner privileges and
   egress.
5. Rotate exposed or potentially exposed credentials.
6. Requalify the complete runner/browser/Java/Playwright/target combination before return to service.

### Suspected credential exposure

1. Disable the provider or remove the runner from service to prevent new resolutions.
2. Rotate the affected operator-owned values at their source; do not mutate historical snapshots or
   admitted logical references.
3. Review access-controlled host and platform records without copying values into tickets or logs.
4. Remove obsolete values from the runner process environment by restarting or replacing the
   affected process under operator control.
5. Requalify with newly injected values and retain only sanitized evidence.

### Cleanup failure and runner quarantine

1. Treat cleanup failure as infrastructure `ERROR`, never `PASSED` or assertion `FAILED`.
2. Quarantine the runner immediately.
3. Verify browser processes, contexts, pages, workspace handles, resolved-secret handles, secret
   scope, and physical workspace have been released.
4. Do not expose local paths, process output, page state, or secret-bearing diagnostics.
5. Repair or replace the runner and repeat inert verification before any authorized requalification.

## Rollback and Credential Rotation

AS-025 adds no database migration and no alternate durable execution history. Rollback is therefore
operator configuration and runner eligibility, not history rewriting:

1. Disable `automation.runner.secrets.operator-environment.enabled` or withdraw the runner from the
   eligible pool.
2. Restore the last qualified browser package and absolute executable configuration under operator
   change control.
3. Remove authorization for the business scenario or target when its approval is withdrawn.
4. Preserve admitted snapshots, execution attempts, normalized terminal states, and lease history.
5. Never edit a durable result, reuse a claim token, bypass fencing, or force a stale completion.

Credential rotation changes the operator-owned value while retaining the same admitted logical
reference. Stop or drain affected runner processes, replace the environment-injected value through
the approved operator channel, restart with least privilege, and perform an authorized bounded
qualification. Do not place old or new values in source, commands, tickets, logs, reports, or
evidence. Automated rotation, renewable leases, and secret-manager administration remain deferred.

## Release Checklist

### Qualification and supported boundary

- [ ] The AS-025G real-browser qualification is accepted for the exact recorded runner, Java,
  Playwright, browser, and target combination.
- [ ] The browser is operator-provisioned, its source and build are reviewed, and no download or
  installation fallback exists.
- [ ] The target authorization is current, non-production, manual, bounded, and excluded from CI and
  recurring execution.

### Documentation and security

- [ ] Requirements, ADR-015, implementation plan, development log, roadmap, deployment guidance,
  and this runbook agree with the repository.
- [ ] Operator configuration contains no committed machine path, credential value, provider key, or
  account identifier.
- [ ] Threat controls and residual risks, including environment exposure and transient SDK strings,
  are reviewed and explicitly accepted.
- [ ] Incident response, rollback, credential rotation, and runner quarantine ownership are assigned.
- [ ] Public logs, results, evidence, and diagnostics contain no prohibited sensitive material.

### Lifecycle and cleanup

- [ ] `RunnerPipelineCoordinator` remains the authoritative controlled execution path.
- [ ] `RunnerExecutionService` remains the fenced persistence authority, with no stale terminal write
  after ownership loss.
- [ ] `ExecutionOrchestrator` remains provider-neutral and OrangeHRM remains declarative data only.
- [ ] Browser/session, workspace access, secret handles, secret scope, and physical workspace cleanup
  evidence is reviewed.

### Verification and approval

- [ ] Focused AS-025 tests pass without browser launch, target access, or operator-secret resolution.
- [ ] Compilation and the ordinary full Maven suite pass in inert mode.
- [ ] Reviewed skips are expected and no browser was installed or downloaded.
- [ ] Existing schema `1.0` and schema `2.0` compatibility evidence remains green.
- [ ] An independent architecture and security review finds no unresolved blocking issue.
- [ ] Every AS-025 acceptance criterion has evidence and deferred scope remains deferred.
- [ ] Final feature approval is recorded before any separately authorized commit or push.
