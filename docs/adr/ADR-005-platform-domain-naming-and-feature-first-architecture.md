# ADR-005: Platform Domain Naming and Feature-First Architecture

## Status

Accepted

## Context

Automation Studio needs a stable platform vocabulary for automation assets while supporting
multiple execution technologies. The current backend contains a legacy `TestSuite` entity and
`test_suite` table. Repository investigation shows that this is an incomplete earlier version of
the platform-managed suite concept: it is Project-owned, independently persisted, and referenced
by `Execution`, but it lacks the API and newer metadata required by AS-015. It is not an
engine-native object or an execution-only record.

The backend currently organizes implemented Project CRUD code by technical layer under
`com.automationstudio.api`. AS-015 begins a cohesive automation capability with several related
types and requires a package boundary that remains understandable as the capability grows.

ADR-001 establishes an engine-independent control plane and an engine contract. This decision
refines the platform vocabulary and source organization; it does not implement engine adapters,
plugin discovery, or runtime plugin loading.

## Decision

### Platform-owned automation domain terminology

`Workspace` and `Project` remain generic platform names. Automation-specific platform concepts
use explicit automation terminology:

- `AutomationSuite`
- `AutomationTestCase`
- `AutomationExecution`
- `AutomationEnvironment`
- `AutomationEngine`

AS-015 introduces only `AutomationSuite`. The other names establish platform vocabulary for
future decisions and do not imply that each concept will be implemented as a persisted entity.

Java types for AS-015 use `AutomationSuite` consistently, including
`AutomationSuiteController`, `AutomationSuiteService`, `AutomationSuiteRepository`, and
`AutomationSuiteMapper`.

### AutomationSuite versus engine-native suite concepts

`AutomationSuite` is the platform-owned grouping and configuration resource attached to a
Project. It is not a Playwright project, Selenium suite, Karate feature collection, REST Assured
test class, Appium capability set, or another engine's native suite representation.

Engine-native concepts and translations remain inside engine adapters or plugins. An adapter may
map an `AutomationSuite` to its native execution model, but native types must not leak into the
core entity, API DTOs, or persistence contracts.

The existing `TestSuite` is an incomplete earlier implementation of this same platform aggregate.
Its UUID identifies the suite selected by existing executions. AS-015 therefore evolves the
logical aggregate rather than creating a second writable suite resource.

### Staged persistence migration

Automation Suite persistence will use an expand-and-contract migration:

1. Expand the existing `test_suite` table with backward-compatible fields.
2. Inspect and map legacy data without discarding suite identities.
3. Move Java and REST terminology to `AutomationSuite` while retaining transitional physical
   names.
4. Observe compatibility before renaming or removing database objects.
5. Rename physical objects only in a later compatibility-gated migration.

The physical names `test_suite` and `execution.test_suite_id` remain during the initial
transition. Java and REST terminology may use `AutomationSuite` before those database objects are
renamed. Existing suite UUIDs and historical execution relationships must be preserved.

The later contract migration may rename the table, execution foreign-key column, constraints, and
indexes only after deployed data and consumers have been verified. The repository currently uses
singular table names such as `project`, `environment`, `test_suite`, and `execution`. The eventual
physical Automation Suite table name must be decided consistently with that convention or must
explicitly supersede it.

### Feature-first package organization

New AS-015 Java code will use a feature-first root beneath the existing application package:

```text
com.automationstudio.api.automation.suite
```

Feature-internal subpackages may separate controller, DTO, entity, mapper, repository, and service
types when that improves navigation. All Automation Suite behavior remains within the feature
boundary. Existing Project and Workspace code is not reorganized as part of AS-015.

### Stable engineId strategy

`AutomationSuite` stores a required, stable `engineId` string. The identifier is an opaque
platform-to-engine reference, not a Java enum and not a framework class name. Adding an engine
must not require changing a platform enum or the Automation Suite schema.

Engine identifier ownership, registration, availability validation, and compatibility rules are
future engine-catalog concerns. AS-015 validates only the identifier's required form and length;
it does not claim that dynamic plugin registration or runtime plugin loading exists.

### Engine-agnostic core

The platform core must not depend on Playwright, Selenium, Karate, REST Assured, Appium, or other
engine libraries. Platform-owned `SuiteType` and `AutomationSuiteStatus` enums may describe stable
platform workflow and classification. Engine-specific configuration is preserved as JSONB without
interpreting it as engine-native Java types.

Adding a `SuiteType` value changes the platform taxonomy and requires a platform release. Adding a
new `engineId` does not require a `SuiteType` change when an existing type describes it.

## Consequences

### Positive

- Platform APIs and persistence use one explicit automation vocabulary.
- Engine integrations can evolve without changing core enums for every framework.
- JSON configuration can be preserved without introducing engine dependencies into the API.
- Feature-first organization gives future suite DTOs, rules, persistence, and tests a cohesive
  boundary.
- Generic Workspace and Project concepts remain reusable and unchanged.
- Existing suite identities and execution history remain connected throughout the transition.
- Separating semantic terminology from physical renaming reduces deployment and rollback risk.

### Trade-offs

- Java/API and database terminology temporarily differ during the compatibility period.
- Legacy columns and values require explicit inventory, mapping, and validation before new
  constraints can become authoritative.
- Stable `engineId` governance will eventually require an engine catalog or equivalent contract.
- JSONB provides flexibility but requires engine-owned schema validation in a future phase.
- Feature-first and existing layer-first packages will coexist while older features remain in
  their current structure.
- Suites referenced by historical executions cannot be physically deleted under the current
  `ON DELETE RESTRICT` foreign key.

## Alternatives Considered

### Retain TestSuite as the platform entity

Rejected because the name is easily confused with engine-native suite constructs and does not
establish the approved platform automation vocabulary.

### Create a separate automation_suites aggregate

Rejected because the existing `TestSuite` already represents the logical platform suite selected
by executions. A second writable table would create competing sources of truth, independent name
constraints, divergent identifiers, and a later reconciliation problem before new suites could be
used by the execution model.

### Rename and contract the legacy schema immediately

Rejected because deployed data and external consumers are not known. Legacy engine values,
`suite_reference`, `INACTIVE` status, field lengths, and normalized-name collisions require
approved mappings before destructive or narrowing changes. Combining those decisions with table
and foreign-key renames would make deployment and rollback unnecessarily risky.

### Use a hard-coded framework enum

Rejected because every additional engine would require a core platform release and persistence
change, coupling the control plane to its adapters.

### Store engine-specific typed configuration in the core model

Rejected because it would introduce framework dependencies and require core DTO/schema changes
for engine-specific options.

### Continue layer-first organization for AS-015

Rejected for this feature because suite behavior, DTOs, mapping, persistence, and tests form one
domain capability that will grow independently. Existing features are not moved merely for
symmetry.

## Future Implications

- Deployed-data and consumer inventory is an implementation gate before schema expansion is made
  authoritative.
- Mapping decisions for legacy engine values, suite references, suite types, lifecycle status,
  overlength values, and normalized-name collisions require explicit approval; this ADR does not
  infer them.
- Existing suite UUIDs and `execution.test_suite_id` relationships must survive the Java/API
  terminology cutover.
- A later compatibility-gated migration will decide and apply physical table, column, constraint,
  and index renames.
- Deletion remains restricted while an Execution references a suite. AS-016 must additionally
  revisit deletion once Automation Test Cases depend on suites.
- A future engine-catalog decision must define identifier registration, availability, versioning,
  and engine-specific configuration validation.
- Runtime plugin loading, runner behavior, scheduling, and execution translation require separate
  implementation work and are not delivered by this ADR.
- Future automation entities should follow the same platform-owned naming and feature boundary
  unless superseded by another accepted ADR.

## Implementation Outcome Amendment (2026-07-19)

AS-015 retained the architectural naming and migration direction in this ADR, but its delivered
contract differs from two original decisions. This amendment records the outcome without rewriting
the decision history above.

### Source organization

AS-015 production code follows the repository's existing layer-first packages under
`com.automationstudio.api`: controller, domain, DTO, entity, mapper, repository, and service.
The proposed `com.automationstudio.api.automation.suite` feature-first root was not introduced.
Reorganizing packages is deferred to separate work so that a structural migration can be planned
and reviewed independently.

### Transitional engine contract

The delivered API preserves the existing required `engineType` and `suiteReference` fields while
adding an optional string `engineId`. The original decision described `engineId` as required; the
implementation keeps it nullable during the compatibility period because legacy rows and clients
still use the transitional fields. `engineId` remains an opaque string with a maximum length of
100. No engine enum or Engine Registry was introduced. Registry ownership, availability checks,
and engine-specific configuration validation remain future work.

All other migration boundaries remain in force: `AutomationSuite` is the Java/API term for the
single logical aggregate, the PostgreSQL table remains `test_suite`, and executions continue to
use the physical `execution.test_suite_id` foreign-key column. Physical database renaming remains
a later compatibility-gated change.
