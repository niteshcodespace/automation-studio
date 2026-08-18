# AS-031 Selenium Java Engine Plugin Implementation Plan

## Status and delivery controls

AS-031 starts from merged AS-030 commit `bc2842b9a7acc456f6d8a2855e24910999a46ac9`
on `feature/AS-031-selenium-engine-plugin`. AS-031A is documentation-only and uncommitted.

Every sub-story requires bounded implementation, focused verification, security/architecture/
compatibility review, repository checkpoint, and separate commit approval. Commit, push, PR, and
merge are never implicit. A contract gap in the SDK, orchestrator, lifecycle, workspace, secret,
artifact, persistence, or network authority is a stop condition rather than permission to expand.

## AS-031A - Architecture, Requirements, and Security Contract

**Objective:** Define the complete v1 product, source, dependency, driver/browser, isolation,
network, secret, resource, result, artifact, lifecycle, registration, verification, and delivery
contract before adding Selenium.

**Scope:** Requirements, ADR-021, this plan, and development log only.

**Security:** Decide passive declarative source, provider-neutral execution control, disposable
digest-pinned browser worker, browser-specific forced egress gateway, loopback-only driver service,
unsafe-artifact exclusions, sequential execution, independently forced teardown, and runtime-
identity-based absence verification.

**Tests:** `git diff --check`, file-scope and prohibited-change inspection. No Maven, Docker,
browser, driver, network, or executable verification.

**Dependency:** AS-030 merged at `bc2842b`.

**Exit:** Four documents are internally consistent; no blocker is hidden; only intended Markdown
files changed; AS-031B remains unstarted.

## AS-031B - Provider-Neutral Execution Control and Supervision Prerequisite

**Objective:** Close the actual SDK/orchestrator gap before any Selenium dependency or provider
module exists.

**Implementation:** Add a JDK-only execution-control capability with absolute deadline, monotonic
remaining time, cancellation observation, and bounded teardown registration; project it through the
canonical orchestrator; add one platform supervisor that arbitrates completion/cancellation/
deadline and can invoke teardown independently. Preserve source-compatible defaults for existing
providers. No Selenium, browser, driver, worker image, or network implementation.

The existing request construction path remains available through a compatibility constructor or
factory. Compatibility/no-op control is test/legacy-only and never represents an infinite
production deadline; canonical production orchestration always supplies a live bounded control.
The one registration is a composite supervisor-owned handle that atomically accumulates partial-
startup resources without exposing runtime-specific SDK types.

**Tests:** SDK/conformance identity and redaction, constructor/factory source compatibility across
Builtin/Playwright/REST Assured/Karate/sample fixtures, deadline monotonicity, cancellation races,
late-result rejection, composite partial-startup teardown, unresponsive provider teardown,
idempotent close, and canonical terminal-state precedence.

**Security:** The capability exposes no platform credential or runtime technology object. Forced
teardown is execution-scoped, single-registration, bounded, race-safe, and cannot alter persistence
or terminal state directly.

**Dependency:** Accepted and independently re-verified AS-031A remediation.

**Exit:** The platform, not a provider, demonstrably owns deadline/cancellation/termination. Focused
and full-reactor verification plus security/architecture/compatibility review pass.

## AS-031C - Provider Module, Dependency, Manifest, and Conformance Foundation

**Objective:** Create the provider-local module and passive source contract without browser execution.

**Implementation:** Reactor module, pinned Selenium dependency after resolved-tree/license/
vulnerability review, descriptor `selenium-java`/`1.0.0`, schema `1.0`, bounded manifest loading,
immutable scenario/action identities, strict suite configuration, exception taxonomy, inert
execution seam, and conformance fixture. No worker/browser/driver creation or registration.

**Tests:** Identity, descriptor, dependency boundary, SDK conformance, sanitized exceptions,
reflection/dependency leakage, and ordinary browser-inert reactor tests.

**Security:** Prove Selenium types stay provider-local and Selenium Manager cannot become an
implicit execution authority.

**Dependency:** Accepted AS-031B.

**Exit:** Provider module compiles and conforms structurally; no real execution claim; reviews pass.

## AS-031D - Isolated Browser Worker and Network Foundation

**Objective:** Establish independently terminable process and network containment without executing
Selenium scenarios.

**Implementation:** Digest-pinned Linux worker-image contract with build-time qualified Chrome and
ChromeDriver, non-root/read-only/resource-bounded container, private network, browser-specific
egress gateway, bounded IPC, read-only source projection, runtime identities, worker process-group
supervisor, forced teardown, absence inspection, orphan alert, and expected-version transition to runner `DISABLED`. Foundation
proof only; no Selenium scenario execution.

**Tests:** Image identity, absent host/Docker/credential authority, host-network prohibition,
gateway-only same-origin reach, direct IPv4/IPv6, UDP, QUIC/HTTP3, WebRTC/STUN/TURN, alternate-DNS,
direct-IP, proxy-bypass, remote-debugging, unsupported-protocol and private/control-plane denial,
redirect/rebinding/WebSocket enforcement, PID/profile/tmpfs isolation, partial startup, forced
teardown, runtime/network absence, expected-version disable races/failure/recovery, and inert IPC.

**Security:** Production registration remains impossible. The worker network is default-deny for
IPv4/IPv6 and UDP, Chrome QUIC/WebRTC are disabled, DNS is mediation-owned, and the only reachable
execution-local endpoints are explicit gateway/control endpoints. No floating image, host mount,
host networking, Docker socket, proxy bypass/inheritance, external debugging, or broad process
killing is accepted.

**Dependency:** Accepted AS-031C and supported Linux-container deployment prerequisites.

**Exit:** Process/network containment and post-execution absence are independently proved without
claiming Selenium execution.

## AS-031E - Controlled Selenium Execution, Secrets, Results, and Evidence

**Objective:** Execute approved actions inside the accepted worker and complete safe platform
capability use.

**Implementation:** Worker-local fixed executables, loopback/restricted-origin driver service,
headless temporary profile, Selenium adapter, bounded waits, action executors, sequential runner,
execution-control observation, lazy sensitive fill, provider-neutral results, unconditional bounded
JSON report through AS-028, failure propagation, reverse cleanup, and absence verification.

**Tests:** Runtime fakes, real-container/browser opt-in, actions/waits, all-request origin policy,
secret timing/closure/canary leakage, result identity/outcomes/timing, report schema/size/content and
publication-failure precedence to canonical `ERROR` over success/assertion failure/cancellation,
browser/driver crash, cancellation/deadline/unresponsive calls, repeated close,
cleanup precedence, absence, and compatibility.

**Security:** No Manager/PATH/download fallback, external driver bind, proxy, certificate bypass,
capability, extension, DevTools, script, download, upload, page source, unsafe screenshot/log, or
direct network authority.

**Dependency:** Accepted AS-031D.

**Exit:** Controlled sequential browser execution and deterministic cleanup pass independent review.

## AS-031F - Static Lifecycle Integration and Canonical Qualification

**Objective:** Assemble one production provider and prove the complete canonical path.

**Implementation:** Conditional provider bean and existing registry integration plus a default-off
canonical fixture using exact Git source, supervisor, worker, gateway, browser/driver, logical
secrets, result, required AS-028 report, and complete cleanup.

**Tests:** Conditional/duplicate registration, canonical success/failure/cancellation/deadline,
artifact checksum/storage, prohibited leakage, target/network policy, runtime/workspace absence,
runner `DISABLED` transition on unproved absence, and compatibility across existing engines.

**Security:** Page source and general screenshots remain absent; no raw diagnostic or secret crosses
SDK/artifact/persistence boundaries.

**Dependency:** Accepted AS-031E.

**Exit:** One production provider is statically assembled through the canonical path; reviews pass.

## AS-031G - Feature Verification and Documentation Closure

**Objective:** Independently verify all mandatory criteria and reconcile final repository authority.

**Implementation:** Documentation/evidence reconciliation only unless independent review finds a
bounded defect. Reuse OrangeHRM business intent for an explicitly authorized real-target
qualification without copying credentials or provider-specific implementation classes.

**Tests:** Canonical success and bounded failure evidence; process/profile/workspace absence;
focused module suites; security, architecture, and compatibility matrices; one exact-source full
reactor; real target qualification only when separately authorized and safely provisioned.

**Security:** Production policy is not weakened for testability. Fixture-only target/secrets are
inert, local, and non-production.

**Dependency:** Accepted AS-031F.

**Exit:** Every mandatory criterion has independent evidence, no unresolved blocker remains,
documentation is reconciled, and the repository awaits separate commit/push/PR/merge gates.

## Principal risks and stop conditions

- Any requirement to execute repository Java/JUnit/TestNG requires a new hostile-source decision;
  the v1 worker is not silently broadened.
- Any Selenium Manager/PATH/download fallback is blocking.
- Inability to bind/control/terminate the driver and browser process tree is blocking.
- Any need to expose raw capabilities, proxy, certificate bypass, Grid, or remote URLs is blocking.
- Any required unsafe artifact or secret-bearing diagnostic is blocking.
- Any SDK change beyond the accepted provider-neutral AS-031B control capability, or any registry,
  lifecycle, workspace, secret, artifact, persistence, REST, or migration change, stops for review.
- Any direct browser egress, cross-origin page dependency, unverified runtime absence, or inability
  to transition an orphaned runner through version-safe `RunnerManagementService` ownership to
  `RunnerStatus.DISABLED` is blocking.
