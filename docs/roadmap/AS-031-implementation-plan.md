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

**Checkpoint:** Implemented locally for independent review. The SDK control, bounded canonical
construction, virtual-thread supervisor, deadline/cancellation arbitration, one-shot teardown,
late-result rejection, exact observed-version cancellation fencing, and bounded late-registration
teardown protocol are present. Deterministic remediation tests cover non-cooperative providers,
late completion, arbitration, teardown races, and stale cancellation observations. Existing providers
retain their prior behavior. Cancellation-associated operational failures preserve the exact positive
observation through sanitized platform exception metadata and persist `ERROR` through the normal
locked ownership validator. The metadata-bearing exception constructor is private; public callers
cannot supply N, and only package-internal orchestration enrichment after
`PlatformExecutionControl` observes repository state can attach it. The public orchestration result
also excludes N; a separate package-internal platform outcome carries exact observed N from the
final production orchestrator to the coordinator. Alternate orchestrator implementations can
produce ordinary results but cannot manufacture trusted fencing metadata. Positive observation and
probe types plus the probe-injecting orchestrator and trusted coordinator construction paths are
package-internal; package-local Spring assembly supplies only the repository-backed production
probe. PostgreSQL-backed integration
coverage drives the real coordinator, orchestrator, transactional completion service, locked
repositories, and ownership validator, proving the four cancellation/error replacements, stale
N-to-N+1 rejection without overwrite, and unchanged no-cancellation error persistence. Verification remains required
before AS-031B acceptance or AS-031C work.

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

**Checkpoint:** Focused independent-review remediation is implemented locally and remains
uncommitted pending re-review. The parser rejects before parsing or constructing global step 5,001,
and deterministic conformance rejection now validates provider-specific failure identity through a
provider-neutral type-plus-assertion expectation. Focused online/offline and provider compatibility
reactors pass, as does a fresh backend compile. The root test failure remains classified as
pre-existing; no backend repair was attempted. Dependency, license, vulnerability, provenance,
checksum, inertness, and the unchanged Selenium Manager native-payload observation are recorded in
the development log. AS-031D has not started.

**Objective:** Create the provider-local module and passive source contract without browser execution.

**Implementation:** Reactor module, pinned Selenium dependency after resolved-tree/license/
vulnerability review, descriptor `selenium-java`/`1.0.0`, schema `1.0`, bounded manifest loading,
immutable scenario/action identities, strict suite configuration, exception taxonomy, inert
execution seam, and conformance fixture. No worker/browser/driver creation or registration.

**Tests:** Identity, descriptor, dependency boundary, SDK conformance, exact deterministic rejection
identity, 5,000/5,001 global-step boundary and early sanitized rejection, sanitized exceptions,
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

### AS-031D1 implementation checkpoint

Resource identity, worker lifecycle, and bounded IPC are implemented locally. Evidence covers the
JDK-only worker module/image contract, strict fixed control protocol, immutable execution/container
identity, digest-only platform-owned Docker argv, pre-acquisition composite teardown, partial and
concurrent cleanup, fail-closed label/ID verification, and sanitized absence semantics. The
foundation uses `--network none` solely to start an isolated worker; D2 network topology and its
security acceptance have not started. Browser/driver execution, provider activation, backend
integration, and later AS-031 stages remain excluded.

Local verification: worker 7/7; focused conformance 17/17 and Selenium 30/30; compatibility sample
9/9, REST Assured 36/36, Karate 36 pass plus its existing conditional skip, and Selenium 30/30.

Focused remediation after independent review coordinates startup and teardown through one explicit
resource state machine, gives all cleanup work one 1.5-second deadline below the AS-031B platform
bound, terminates exact Docker/attach processes before bounded reader joining, and makes immutable
container-ID inspection authoritative for absence. Deterministic concurrency, timeout, identity,
and partial-lifecycle verification is required before this checkpoint is ready for re-review. D1 is
not approved here, and D2 has not started.

Final remediation stores one absolute deadline for the complete termination attempt. No late
handoff constructs another cleanup budget: exact worker or attach ownership arriving too late is
retained as `OWNED_BUT_UNRESOLVED`, and the single immutable terminal report remains unsafe.
Startup waiting is capped to preserve most of the 1.5-second budget for resource cleanup. The
focused A-M matrix exercises every specified lifecycle boundary without claiming D1 approval or
starting D2.

Final deadline propagation uses one immutable monotonic deadline object across the aggregate,
Docker command runner, attach IPC, process termination, and all cleanup callers. No lower cleanup
layer resets the timeout. Strengthened deterministic evidence covers the real create/ownership seam,
worker/teardown latching, fake-ticker expiry, process-backed attach failure, and exact blocked child
and reader termination. This remains a local re-review checkpoint, not D1 approval; D2 is unstarted.

ProcessAttach hard-bound remediation replaces the earlier virtual-thread/task-scope claim. A blocked
Windows process-pipe operation is supervised by narrow daemon platform threads; no executor close can
join a helper after the deadline. Graceful SHUTDOWN/BYE receives at most 100 ms inside the original
budget, after which exact-process destroy/forced destroy and supervised stream closure take priority.
Helper completion is evidence, not a reason to extend the deadline: an unresolved writer, reader, or
closer keeps cleanup unsafe. Separate deterministic tests prove an uninterruptible write and a close
activated only after successful protocol exchange both return at the 250 ms deadline with accurate
unsafe state. Ten-run real timing evidence is 203-237 ms for Matrix L and 105-118 ms for Matrix M;
the real 1.5-second hostile-attach acceptance returned in 111 ms, below two seconds. Focused
attach/matrix verification is 17/17; the required reactor is conformance 17/17, worker 7/7, and
Selenium 51/51. D1 remains awaiting independent approval, and D2 has not started.

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
