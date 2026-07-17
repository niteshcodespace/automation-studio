# Automation Studio Prompt Engineering Guide

This guide defines how engineers and AI agents should frame, review, and reuse prompts for Automation Studio development. It complements the [Automation Studio AI Engineering Playbook](./CODEX.md), which remains authoritative for repository architecture, engineering policy, scope, verification, and delivery.

> **Use this guide to describe the work. Use `CODEX.md` to govern the work.**

## Table of Contents

1. [Introduction](#1-introduction)
2. [Prompt Engineering Principles](#2-prompt-engineering-principles)
3. [Standard Prompt Structure](#3-standard-prompt-structure)
4. [Prompt Templates](#4-prompt-templates)
5. [Examples](#5-examples)
6. [Prompt Patterns](#6-prompt-patterns)
7. [Scope Control](#7-scope-control)
8. [Prompt Anti-Patterns](#8-prompt-anti-patterns)
9. [Verification Prompts](#9-verification-prompts)
10. [Best Practices](#10-best-practices)
11. [Appendix](#11-appendix)

## 1. Introduction

A prompt is an engineering input. Its quality directly affects the relevance, safety, reviewability, and completeness of the resulting change. A vague prompt forces the implementer to infer scope and behavior. A precise prompt makes the desired outcome testable while leaving routine implementation details to repository conventions.

Good prompts do not attempt to encode the entire repository. They identify the outcome, point to the authoritative local evidence, state business rules and boundaries, and define how success will be verified. This lets Codex inspect before editing and keeps the prompt readable enough for a reviewer to audit.

### 1.1 Documentation relationship

| Document | Role | How a prompt should use it |
|---|---|---|
| [`CODEX.md`](./CODEX.md) | Authoritative engineering playbook | Reference it for architecture, workflow, layer ownership, scope rules, testing, Git, and completion reporting. Do not restate it wholesale. |
| `PROMPT_GUIDE.md` | Official prompt-construction handbook | Use its structure, templates, examples, and prompt review criteria. |
| `CODING_STANDARDS.md` | Reserved detailed coding standard | Reference applicable language/framework rules once that document contains approved standards. Until then, rely on `CODEX.md` and existing code. |
| `REVIEW_CHECKLIST.md` | Reserved reusable review checklist | Use it for final review once populated. Until then, use the review guidance in `CODEX.md` and the verification prompts here. |

At the time this guide was created, `CODING_STANDARDS.md` and `REVIEW_CHECKLIST.md` were empty placeholders. A prompt must not claim that an empty document defines rules. It should follow existing implementation and `CODEX.md` instead.

### 1.2 Audience

This guide serves:

- engineers preparing implementation or review requests;
- product and technical leads translating a story into an actionable task;
- AI agents evaluating whether a prompt contains enough evidence and authority;
- reviewers checking whether delivered work matches the original request.

## 2. Prompt Engineering Principles

### 2.1 Clarity

State one primary outcome in the first sentence. Use exact type, method, route, table, and file names where known. Prefer measurable language:

- Good: "Add `findByIdAndWorkspaceId(UUID id, UUID workspaceId)` to `ProjectRepository`."
- Weak: "Improve project lookups."

Do not confuse clarity with prescribing every line of code. Specify observable behavior and critical contracts; allow existing local conventions to determine routine syntax.

### 2.2 Relevant context

Give enough context to explain why the change exists and which boundaries it touches. For Automation Studio, useful context often includes:

- story identifier, such as `AS-013`;
- affected control-plane concept, such as Project or Execution;
- Spring Boot/JPA/Flyway technology constraints;
- workspace or project ownership scope;
- whether the referenced architecture is implemented or future-state.

Do not paste large unrelated design documents. Point Codex to repository files and require inspection.

### 2.3 Explicit scope

Name the files or layers that may change, and explicitly prohibit predictable scope expansion. If only a repository interface should change, say that services, controllers, DTOs, migrations, tests, and the POM must remain untouched.

Scope should be narrow but internally consistent. Do not prohibit a required test while also requiring new behavior to be fully tested unless the task intentionally separates implementation and tests.

### 2.4 Constraints

Constraints describe boundaries, not desired behavior. Examples include:

- do not modify applied Flyway migrations;
- do not change public API routes;
- do not add dependencies;
- preserve UUID identifiers;
- do not commit or push;
- keep the change backward compatible.

Distinguish hard constraints from preferences. Use "must" and "must not" only for requirements a reviewer will enforce.

### 2.5 Expected output

Tell Codex what the final response must contain. Typical outputs are:

- concise implementation summary;
- files changed;
- verification commands and results;
- assumptions or inconsistencies;
- full file content when a handoff requires it;
- confirmation that no commit or push occurred.

The response format should help review the work, not force unnecessary verbosity.

### 2.6 Verification

Define evidence of success. Verification should match the change:

- compilation for signature/import changes;
- focused tests for business behavior;
- repository integration tests for queries;
- Flyway/JPA validation for schema mappings;
- Markdown, link, and diff review for documentation.

Ask for exact commands and honest outcomes. Do not phrase verification as "make sure it works" without defining what that means.

### 2.7 Incremental implementation

Prefer one coherent vertical slice or one layer-specific task over a large multi-feature prompt. Separate unrelated work into separately reviewable prompts. If a feature must span layers, specify the dependency order and acceptance criteria for the complete slice.

Incremental does not mean leaving the repository broken. Each task should end in a compilable, internally consistent state unless explicitly described as a planning or investigation step.

### 2.8 Evidence before assumptions

Require Codex to compare the request with repository evidence. For persistence work, explicitly name the migration and analogous entities. For service work, identify repositories, entities, and business rules. If sources conflict, instruct Codex to report the mismatch instead of guessing.

## 3. Standard Prompt Structure

The following is the official Automation Studio prompt format. Sections may be omitted only when genuinely irrelevant.

```markdown
# Task: <AS-XXX concise outcome>

## Background

<Why this change is needed and what user/system outcome it supports.>

## Repository Context

- Module: <for example, backend/studio-api>
- Technology/boundary: <for example, Spring Data JPA repository>
- Governing guidance: Read `docs/ai/CODEX.md`.

## Files to Inspect

- `<authoritative or analogous file>`
- `<relevant test>`
- `<relevant migration or architecture document>`

## Requirements

1. <Observable requirement>
2. <Exact contract, signature, route, or mapping>

## Business Rules

- <Ownership, state, validation, or compatibility rule>

## Constraints

- Modify only: `<allowed path>`
- Do not modify: <prohibited paths/layers>
- Do not add dependencies.
- Do not commit or push.

## Validation

- Run: `<focused command>`
- Run: `<broader command when applicable>`
- Review the final diff and report checks not run.

## Expected Output

- Concise summary.
- Files changed.
- Verification results.
- Assumptions and inconsistencies.
```

### 3.1 Task

Use an imperative outcome and include the story identifier when available. A task is not a narrative backlog dump.

```text
Task: AS-014 Add workspace-scoped project lookup service
```

### 3.2 Background

Explain business intent in two or three sentences. Avoid implementation detail unless it explains a non-obvious constraint.

### 3.3 Repository Context

Identify the active module and layer. If the task relates to a future architectural component, say whether the request is design-only or authorized implementation.

### 3.4 Files to Inspect

List the minimum evidence needed to work safely. Prefer exact paths. Include:

- the target file, if it exists;
- one or two analogous files;
- relevant tests;
- all migrations affecting a table;
- a focused ADR or architecture section when architectural interpretation matters.

Do not tell Codex to inspect "the whole repository" when a bounded inspection list is sufficient.

### 3.5 Requirements

Write independently verifiable statements. Include signatures and types when they are contractual, but do not prescribe incidental line ordering or private helper names.

### 3.6 Business Rules

Separate business semantics from framework mechanics. Examples:

- a project lookup must be scoped by workspace;
- an environment and test suite must belong to the execution's project;
- terminal execution states cannot return to running;
- execution counts cannot be negative.

### 3.7 Constraints

State allowed files, forbidden files, dependency limits, migration policy, compatibility requirements, and Git permissions. Avoid contradictory constraints.

### 3.8 Validation

Specify the appropriate test or review commands and any manual contract comparison. Let Codex adapt only when the prescribed command is unavailable, and require the alternative to be reported.

### 3.9 Expected Output

Request only information needed for review. When full file content is required for an external handoff, say so explicitly.

## 4. Prompt Templates

Replace angle-bracket placeholders before use. Delete irrelevant sections rather than leaving ambiguous placeholders.

### 4.1 New feature

```markdown
# Task: <AS-XXX Implement feature name>

## Background

<Describe the user outcome and why it belongs in Automation Studio.>

## Repository Context

- Read `docs/ai/CODEX.md`.
- Module: `<module>`
- Owning layer/capability: `<layer or capability>`

## Files to Inspect

- `<closest analogous implementation>`
- `<related entity/repository/service/controller>`
- `<relevant migration and tests>`

## Requirements

1. <Behavior and acceptance criterion>
2. <Boundary contract>
3. <Error behavior>

## Business Rules

- <Ownership, state transition, or invariant>

## Constraints

- Modify/create only the files required for this vertical slice.
- Preserve existing public and persisted contracts unless explicitly changed here.
- Do not add speculative endpoints, abstractions, or dependencies.
- Do not commit or push.

## Validation

- Add focused automated tests for success and failure paths.
- Run `<focused test command>` and `<module test command>`.
- Review schema/API compatibility and final diff.

## Expected Output

- Summary, files changed, tests run, assumptions, and remaining limitations.
```

### 4.2 Bug fix

```markdown
# Task: <BUG-XXX Fix observable failure>

## Background

- Observed behavior: `<what happens>`
- Expected behavior: `<what should happen>`
- Reproduction: `<minimal steps/input>`

## Files to Inspect

- `<suspected implementation>`
- `<related tests and contract>`

## Requirements

1. Determine and report the root cause.
2. Fix the cause without changing unrelated behavior.
3. Add a regression test that fails before the fix.

## Constraints

- Do not broaden this into a refactor.
- Do not weaken validation or remove tests.
- Do not commit or push.

## Validation

- Run the regression test and relevant suite.
- Report the root cause and exact verification results.
```

### 4.3 Refactoring

```markdown
# Task: Refactor <specific component> without behavior change

## Motivation

<Concrete maintainability problem or measured issue.>

## Files to Inspect

- `<target files>`
- `<tests that define current behavior>`

## Invariants

- Public API, database schema, persisted values, and observable behavior remain unchanged.
- Existing tests must continue to pass.

## Scope

- Modify only: `<paths>`
- Explicitly out of scope: `<adjacent cleanup>`

## Validation

- Run characterization tests before and after the change.
- Show why the diff is behavior-preserving.
```

### 4.4 Documentation

```markdown
# Task: Create/update <document>

## Purpose and Audience

<What readers must understand or do after reading.>

## Sources to Inspect

- `docs/ai/CODEX.md`
- `<authoritative implementation or documentation>`

## Required Sections

1. <section>
2. <section>

## Constraints

- Modify only: `<document path>`
- Do not change production code, tests, migrations, or unrelated documentation.
- Describe current and future state accurately.
- Do not commit or push.

## Validation

- Check headings, links, Markdown structure, diff, and repository consistency.
```

### 4.5 Test creation

```markdown
# Task: Add tests for <behavior>

## Behavior Under Test

- <success path>
- <boundary/failure path>
- <business invariant>

## Files to Inspect

- `<production code>`
- `<existing test conventions>`
- `<migration when database behavior matters>`

## Requirements

- Use the narrowest appropriate test type.
- Keep tests deterministic and independent.
- Use PostgreSQL-compatible integration testing for database-specific behavior.
- Do not change production behavior unless a confirmed defect blocks valid testing.

## Validation

- Run the new tests and relevant existing suite.
- Report coverage intent, not only test count.
```

### 4.6 Repository review

```markdown
# Task: Review <scope> for <quality objective>

## Review Scope

- Paths: `<paths>`
- Baseline: `<branch, diff, story, or commit if available>`

## Review Questions

- Does implementation match the acceptance criteria?
- Are schema, entities, validation, and stored enum values aligned?
- Are architecture boundaries and ownership scoping preserved?
- Are tests sufficient and meaningful?

## Constraints

- Perform a read-only review; do not edit files.
- Report findings by severity with file and line references.
- State when no findings are present and identify residual risk.
```

### 4.7 Architecture review

```markdown
# Task: Review <proposal/change> against Automation Studio architecture

## Sources

- `docs/ai/CODEX.md`
- `<relevant ADR>`
- `<relevant architecture document>`
- `<current implementation>`

## Questions

- Which boundary owns the behavior?
- Does the proposal preserve control-plane, runner, engine, AI, and MCP responsibilities?
- Does it introduce coupling, bypass application services, or assume future components exist?
- Is an ADR required?

## Expected Output

- Current-state assessment.
- Target-state fit.
- Risks, alternatives, and recommendation.
- No implementation changes.
```

### 4.8 Code review

```markdown
# Task: Review the changes for <AS-XXX>

## Acceptance Criteria

- <criterion>

## Scope

- Review `<diff or paths>`.
- Inspect related migrations, entities, tests, and contracts as needed.
- Do not modify files.

## Focus

- Correctness and regressions.
- Business-rule placement and ownership scope.
- Persistence/API compatibility.
- Security and secret handling.
- Test gaps and maintainability.

## Expected Output

- Findings first, ordered by severity.
- File and line references for each finding.
- Questions/assumptions and a short residual-risk summary.
```

## 5. Examples

### 5.1 Poor prompt

```text
Add project APIs and clean up the code. Make it enterprise quality and test it.
```

Why it is poor:

- combines API delivery with undefined cleanup;
- does not name routes, behavior, business rules, or ownership scope;
- does not identify files to inspect;
- leaves DTO, service, error, persistence, and test decisions unconstrained;
- provides no measurable validation or output format;
- invites architecture invention and broad refactoring.

### 5.2 Improved prompt

```markdown
# Task: AS-014 Add a workspace-scoped project read endpoint

## Background

Clients need to retrieve a project only within its owning workspace.

## Repository Context

- Read `docs/ai/CODEX.md`.
- Module: `backend/studio-api`
- Use the existing layered packages under `com.automationstudio.api`.

## Files to Inspect

- `Project.java`
- `Workspace.java`
- `ProjectRepository.java`
- Existing controller, service, DTO, mapper, exception, and test styles if implemented.

## Requirements

1. Add `GET /api/v1/workspaces/{workspaceId}/projects/{projectId}`.
2. Return a response DTO, not a JPA entity.
3. Return 404 when the project does not exist in that workspace.
4. Delegate ownership lookup to a service using `findByIdAndWorkspaceId`.

## Constraints

- Do not change entities, migrations, or the POM.
- Do not add unrelated CRUD endpoints or a mapping framework.
- Do not commit or push.

## Validation

- Add focused service and MVC tests for found and not-found behavior.
- Run the relevant tests and `mvn test` from `backend/studio-api`.

## Expected Output

- Summary, files changed, exact test results, and assumptions.
```

Why it is improved:

- defines one user-visible outcome;
- preserves workspace ownership scope;
- identifies layer boundaries and repository evidence;
- blocks likely overengineering;
- defines success and failure behavior;
- makes the final change auditable.

### 5.3 Good focused prompt

```markdown
# Task: AS-013 Extend ProjectRepository

Inspect `Project.java`, `Workspace.java`, and `ProjectRepository.java`.

Add these Spring Data methods with UUID parameters and conventional return types:

- `existsByWorkspaceIdAndName`
- `findAllByWorkspaceId`
- `findByIdAndWorkspaceId`

Modify only `ProjectRepository.java`. Do not change the POM or create another layer.
Run a backend compile check, report the command and result, and do not commit or push.
```

Why it is good:

- the task is intentionally layer-specific and small;
- inspection evidence, exact contracts, file scope, verification, and Git limits are explicit;
- it leaves routine imports and return-type selection to established Spring Data conventions.

## 6. Prompt Patterns

Patterns are short checklists to add to the standard structure. They do not replace repository inspection.

### 6.1 CRUD implementation

Specify:

- which operations are required; never imply all CRUD operations automatically;
- resource scope, especially workspace/project ownership;
- request and response fields;
- validation and uniqueness behavior;
- not-found, conflict, and invalid-input responses;
- archive/inactivate behavior versus physical deletion;
- tests for every requested operation.

Avoid asking for "full CRUD" when delete semantics, update semantics, or authorization are undecided.

### 6.2 Repository changes

Specify:

- entity and identifier type;
- exact predicates, ordering, and scope;
- expected cardinality and return type when contractual;
- whether relationships must be fetched;
- database indexes or constraints relevant to the query;
- repository integration verification when behavior is non-trivial.

Example requirement:

```text
Return `Optional<Project>` from a workspace-scoped ID lookup. Do not load all projects and filter in memory.
```

### 6.3 Service implementation

Specify:

- the use case and transaction boundary;
- repositories/application interfaces it may use;
- ownership and cross-record invariants;
- state transitions and terminal-state behavior;
- error semantics;
- time source requirements;
- unit-test scenarios.

Do not prompt a service to accept or return web-framework objects.

### 6.4 Controller implementation

Specify:

- HTTP method and versioned path;
- request/response DTOs;
- validation and expected status codes;
- service method to delegate to;
- error-contract expectations;
- MVC test cases.

State explicitly that controllers must not access repositories or expose entities.

### 6.5 DTO creation

Specify:

- request versus response purpose;
- exact external fields and types;
- required, optional, and bounded values;
- whether unknown/read-only fields should be ignored or rejected;
- compatibility expectations;
- whether Java records match established local style.

Do not copy an entity wholesale into a DTO.

### 6.6 Flyway migration

Specify:

- the next migration version and descriptive filename only after inspecting existing migrations;
- exact PostgreSQL objects, types, nullability, defaults, constraints, indexes, and foreign-key delete behavior;
- data backfill and safe transition steps for existing rows;
- corresponding entity or enum changes when in scope;
- forward-fix strategy; never request edits to an applied migration;
- migration and JPA validation tests.

Example inspection clause:

```text
Read every migration affecting `execution` and compare the final schema with `Execution.java` before editing.
```

### 6.7 Test writing

Specify:

- behavior and risk being proven;
- appropriate unit, MVC, repository, integration, or migration test level;
- success, boundary, and failure cases;
- deterministic time and data requirements;
- database compatibility requirements;
- exact test command.

Avoid prompts that optimize for coverage percentage without identifying meaningful behavior.

### 6.8 Documentation updates

Specify:

- target audience and reader outcome;
- authoritative source files;
- required sections and examples;
- current-state versus future-state labeling;
- files that must not change;
- Markdown/link/diff verification.

Reference this guide or `CODEX.md` rather than duplicating their full rules into a new document.

## 7. Scope Control

A well-scoped prompt answers four questions:

1. What outcome is required?
2. What evidence must be inspected?
3. What may change?
4. What must remain unchanged?

### 7.1 File boundary

Use exact paths when the task is intentionally narrow:

```text
Modify only:
- backend/studio-api/src/main/java/com/automationstudio/api/repository/ProjectRepository.java
```

For a vertical slice where exact files are not known, authorize layers and require Codex to list planned files before broadening beyond them.

### 7.2 Architecture boundary

State the owning layer and forbid bypasses:

```text
Business validation belongs in the service. The controller must delegate and the repository must remain persistence-only.
```

Do not ask Codex to redesign package organization, introduce microservices, or implement future runner/AI/MCP components as part of ordinary feature work.

### 7.3 Change boundary

Useful constraints include:

- no unrelated refactoring or formatting;
- no dependency or POM changes;
- no migration changes unless explicitly required;
- no speculative repositories, services, DTOs, controllers, or endpoints;
- no public API changes outside the named contract;
- no commit or push.

### 7.4 Ambiguity boundary

Tell Codex how to respond to conflicts:

```text
If the requested model conflicts with an applied Flyway migration, stop and report each mismatch instead of guessing.
```

This is especially important for identifier types, enum values, nullability, column names, and state transitions.

### 7.5 Dirty-worktree awareness

When working in an active repository, include:

```text
Preserve pre-existing worktree changes. Report them separately from files changed for this task.
```

This prevents an inaccurate scope confirmation and discourages destructive cleanup.

## 8. Prompt Anti-Patterns

| Anti-pattern | Risk | Better wording |
|---|---|---|
| "Implement this properly." | No measurable outcome. | List observable acceptance criteria and validation. |
| "Use best practices." | Invites personal preference over repository convention. | "Follow `CODEX.md` and analogous local code." |
| "Review the repo and build everything needed." | Unbounded inspection and speculative implementation. | Name the module, files, layers, and exact outcome. |
| "Clean up anything you notice." | Broad unrelated refactoring. | Create separate follow-up findings; change only requested files. |
| Missing files to inspect | Schema or local conventions may be missed. | Name target, analogues, migrations, and tests. |
| Missing constraints | Codex may add dependencies or adjacent layers. | State allowed and prohibited changes. |
| Multiple unrelated tasks | Large, hard-to-review diff and unclear verification. | Split into independently coherent prompts. |
| "Redesign this as microservices." | Bypasses approved modular-monolith direction. | Request an architecture review or ADR proposal first. |
| "Fix tests until green." | May weaken tests or hide the defect. | Diagnose the failure, preserve intent, and report root cause. |
| "Update V2 migration." | Breaks applied schema history. | Add a new forward migration after inspecting the chain. |
| Contradictory requirements | Forces guessing. | Resolve contradictions or explicitly request a mismatch report. |
| Over-prescribing every line | Prevents use of valid local conventions and becomes brittle. | Specify contracts and observable behavior. |
| No expected output | Review evidence is inconsistent. | Ask for summary, files, checks, assumptions, and limitations. |

### 8.1 Architecture redesign disguised as feature work

Bad:

```text
Add the project endpoint and reorganize the backend by business module while you are there.
```

Better:

```text
Implement the project endpoint using the current layered packages. Record any modularization recommendation as a non-blocking follow-up; do not reorganize packages.
```

### 8.2 Framework-first prompting

Bad:

```text
Use MapStruct, a generic base service, and a specification framework for this endpoint.
```

Better:

```text
Use existing dependencies and local patterns. Introduce no framework unless the acceptance criteria cannot be met without it; report that blocker before changing the POM.
```

## 9. Verification Prompts

Verification prompts are read-only unless they explicitly authorize fixes. They should request evidence and prioritize findings over summaries.

### 9.1 Code review

```markdown
Review the changes for `<story>` against its acceptance criteria and `docs/ai/CODEX.md`.

Inspect the diff plus directly related code, tests, and migrations. Do not modify files.
Report correctness, regression, security, persistence, API, and test findings in severity order with file/line references. End with assumptions and residual risk. If there are no findings, say so explicitly.
```

### 9.2 Architecture review

```markdown
Perform a read-only architecture review of `<proposal or paths>`.

Compare current implementation with `CODEX.md`, the relevant accepted ADR, and architecture documents. Distinguish implemented and future state. Identify boundary violations, coupling, ownership ambiguity, and whether a new ADR is required. Recommend the smallest architecture-preserving option.
```

### 9.3 Repository review

```markdown
Review `<paths/module>` for consistency with repository conventions.

Check package organization, naming, dependencies, configuration, test structure, and documentation alignment. Do not edit. Separate confirmed issues from future recommendations and cite evidence for each finding.
```

### 9.4 Performance review

```markdown
Perform a read-only performance review of `<use case or diff>`.

Inspect query shape, lazy-loading behavior, N+1 risk, pagination, indexes, transaction size, blocking work, and unnecessary allocations. Do not speculate about bottlenecks without evidence. Classify findings as measured, strongly inferred, or requiring profiling, and propose focused validation.
```

### 9.5 Security review

```markdown
Perform a read-only security review of `<scope>`.

Check input validation, authorization/ownership scope, secret handling, logging, artifact access, injection risks, mass assignment, error disclosure, and dependency/configuration exposure. Relate findings to Automation Studio's control-plane and future AI/MCP boundaries. Report exploitable paths, impact, evidence, and remediation priority.
```

### 9.6 Documentation review

```markdown
Review `<document>` for accuracy, usability, and repository consistency.

Verify links, headings, examples, terminology, current-versus-future state, and agreement with implementation and `CODEX.md`. Identify duplicated policy and stale claims. Do not edit. Report findings with section references and proposed wording direction.
```

## 10. Best Practices

- Start with one outcome and one owner.
- Include the story identifier when available.
- Reference `CODEX.md`; do not paste it into each prompt.
- Point to exact repository evidence.
- Separate requirements, business rules, and constraints.
- State file or layer scope explicitly.
- Define both success and failure behavior.
- Use repository terminology: Workspace, Project, Environment, TestSuite, Execution, ExecutionStep, and ExecutionArtifact.
- Name compatibility-sensitive contracts such as UUIDs, stored enums, routes, and migrations.
- Ask for focused tests and exact command results.
- Tell Codex to preserve pre-existing worktree changes.
- Require mismatch reporting rather than guessing.
- Split unrelated outcomes into separate prompts.
- Keep templates reusable, but tailor every placeholder.
- Request a concise, evidence-based completion report.
- Review the prompt for contradictions before sending it.

### 10.1 Prompt self-review questions

Before sending a prompt, ask:

- Can a reviewer tell exactly what “done” means?
- Is the business outcome separate from implementation detail?
- Are authoritative files named?
- Is workspace/project scope explicit where relevant?
- Are allowed and prohibited changes compatible?
- Does verification match the risk?
- Could the prompt accidentally authorize architecture change or speculative code?
- Does the expected output provide enough evidence to review completion?

## 11. Appendix

### 11.1 Quick-reference prompt card

```text
TASK
One imperative, reviewable outcome.

BACKGROUND
Why the outcome matters.

CONTEXT
Module, layer, and governing repository guidance.

INSPECT
Target, analogues, tests, migrations, and focused architecture sources.

REQUIRE
Observable behavior, contracts, and failure cases.

BUSINESS RULES
Ownership, transitions, invariants, and compatibility.

CONSTRAIN
Allowed files/layers, prohibited changes, dependencies, Git permissions.

VALIDATE
Focused tests, broader checks, manual comparisons, final diff.

REPORT
Summary, files, exact results, assumptions, inconsistencies, scope.
```

### 11.2 Minimum viable prompt

For a small, low-risk task, use:

```markdown
# Task: <specific outcome>

Read `docs/ai/CODEX.md`, `<target>`, and `<closest analogue>`.

Requirements:
- <observable requirement>
- <contract or boundary>

Modify only `<allowed files>`. Do not `<likely scope expansion>`. Preserve existing worktree changes. Do not commit or push.

Run `<verification>`. Return a concise summary, exact verification result, assumptions, and scope confirmation.
```

### 11.3 Prompt quality checklist

- [ ] One coherent outcome.
- [ ] Business reason is clear.
- [ ] Module and owning layer identified.
- [ ] `CODEX.md` referenced.
- [ ] Relevant files to inspect listed.
- [ ] Requirements are measurable.
- [ ] Business rules are explicit.
- [ ] Allowed and forbidden changes are clear.
- [ ] No contradictions or speculative extras.
- [ ] Verification commands are appropriate.
- [ ] Expected output supports review.
- [ ] Commit/push authority is explicit.

### 11.4 Compact good-versus-bad guide

| Instead of | Write |
|---|---|
| "Add CRUD." | Name required operations, routes, scoping, validation, and errors. |
| "Fix the repository." | Name the query behavior, entity, parameters, return type, and test. |
| "Follow best practices." | Reference `CODEX.md` and analogous local files. |
| "Make tests pass." | Reproduce, diagnose, preserve test intent, fix root cause, and verify. |
| "Update the database." | Name a new Flyway migration, final schema, backfill, and constraints. |
| "Review this." | Define review scope, questions, evidence, severity format, and no-edit constraint. |

The best Automation Studio prompt is not the longest. It is the shortest prompt that makes the outcome, evidence, business rules, boundaries, verification, and handoff unambiguous.
