# Automation Studio — Master Feature Roadmap

## 1. Purpose

This document is the authoritative long-term feature roadmap for Automation Studio.

It exists so that:

* feature numbers remain stable;
* completed work is not planned again;
* future conversations do not depend on external notes;
* every feature can be traced to an epic;
* implementation details can evolve without changing the platform direction;
* and roadmap changes are explicitly documented and reviewed.

This roadmap defines strategic feature intent. Detailed requirements, ADRs, implementation plans, and development logs remain authoritative for individual features.

---

# 2. Platform Vision

Automation Studio is an AI-native enterprise automation platform that allows teams to:

* manage automation projects and suites;
* configure execution environments;
* execute automation through distributed runners;
* support multiple automation engines;
* collect execution results and artifacts;
* monitor automation health;
* secure projects, environments, and secrets;
* integrate with source-control and communication platforms;
* and use AI to assist with test creation and failure analysis.

AI capabilities remain advisory.

Deterministic platform services and runners remain responsible for:

* scheduling;
* runner selection;
* execution claims;
* fencing;
* source preparation;
* secret resolution;
* engine execution;
* result persistence;
* retries;
* cancellation;
* and cleanup.

---

# 3. Architectural Principles

All future epics and features must preserve these principles:

1. Repository-first development.
2. No invented requirements.
3. Provider-neutral architecture.
4. String-based `engineId`; no fixed engine enum.
5. One authoritative Engine Registry.
6. One authoritative execution orchestration path.
7. Immutable execution snapshots.
8. Execution-scoped secret isolation.
9. Runner claim and fencing guarantees.
10. Deterministic browser, workspace, process, and artifact cleanup.
11. Secrets stored and transported only by reference.
12. No resolved secret values in snapshots, logs, persistence, reports, or artifacts.
13. Engines remain replaceable implementation plugins.
14. AI remains evidence-grounded and advisory.
15. Every feature remains independently verifiable.
16. Commit, push, PR, and merge remain separately approved gates.

---

# 4. Current Completed Platform Baseline

The exact history remains recorded in feature requirements, ADRs, implementation plans, and development logs.

The platform currently includes:

* Workspace Management
* Project Management
* Automation Suite Management
* Automation Test Case Management
* Environment Management
* Execution Management
* Runner Registry
* Runner Scheduling
* Runner Claim
* Runner Workspace and Source Preparation
* Runner Execution Orchestration
* Playwright Java Execution Engine
* Execution-Scoped Secret Boundary
* Declarative Browser Automation
* OrangeHRM Smoke Automation
* Controlled End-to-End Runner Pipeline
* Real-Browser Qualification
* Production-Readiness Documentation

The latest completed feature is:

```text
AS-025 — First Business Automation: OrangeHRM Smoke Execution
```

AS-026 begins the next planned development phase.

---

# 5. Epic 1 — Engine Platform and Plugin Architecture

## Epic objective

Create a formal, provider-neutral engine plugin platform that supports multiple automation technologies without introducing separate orchestration paths.

## AS-026 — Engine Registry and Plugin Contract

Formalize and reconcile the engine architecture introduced through AS-022 to AS-025.

Expected capability areas:

* string-based engine identity;
* engine version identity;
* engine registration;
* duplicate-registration prevention;
* deterministic engine resolution;
* unsupported-engine handling;
* provider-neutral execution requests and results;
* engine capability declaration;
* plugin compatibility rules;
* engine configuration validation;
* workspace-access contract;
* source-preparation contract;
* secret-access contract;
* result and artifact contract;
* cleanup responsibilities;
* plugin conformance testing;
* and future engine onboarding documentation.

AS-026 must first determine which parts already exist and which parts require hardening.

## AS-027 — Engine Plugin SDK and Conformance Harness

Create a reusable development and test contract for new engines.

Potential capabilities:

* engine plugin interfaces;
* reusable contract tests;
* execution request fixtures;
* result conformance tests;
* failure taxonomy tests;
* cleanup verification;
* thread-safety verification;
* secret-boundary verification;
* unsupported-configuration tests;
* plugin developer documentation;
* and sample engine implementation.

## AS-028 — Execution Artifact and Evidence Contract

Standardize artifacts produced by all engines.

Potential capabilities:

* artifact metadata model;
* artifact categories;
* screenshots;
* videos;
* console logs;
* network traces;
* execution logs;
* engine-native reports;
* attachment size limits;
* media-type validation;
* retention references;
* artifact integrity;
* redaction rules;
* and provider-neutral artifact discovery.

---

# 6. Epic 2 — API Automation Engines

## Epic objective

Add API-testing engines through the common Engine Registry and plugin contract.

## AS-029 — REST Assured Engine Plugin

Support Java-based REST API automation.

Potential capabilities:

* HTTP methods;
* headers;
* query parameters;
* path parameters;
* request bodies;
* authentication references;
* response status validation;
* response header validation;
* JSON body assertions;
* schema validation;
* request and response evidence;
* correlation identifiers;
* retries controlled by the platform;
* and sanitized reporting.

## AS-030 — Karate Engine Plugin

Support Karate test execution through the existing runner pipeline.

Potential capabilities:

* Karate feature discovery;
* tags;
* environment configuration;
* variable injection;
* secret-reference resolution;
* parallel-scenario configuration;
* JUnit-compatible results;
* Karate HTML artifacts;
* API and UI compatibility boundaries;
* and failure normalization.

---

# 7. Epic 3 — Additional Browser and Mobile Engines

## Epic objective

Expand UI and mobile automation while preserving one execution lifecycle.

## AS-031 — Selenium Java Engine Plugin

Add Selenium as the second browser engine.

Potential capabilities:

* WebDriver lifecycle;
* Chrome support;
* Firefox support;
* browser configuration;
* navigation;
* click;
* normal fill;
* sensitive fill;
* selectors;
* waits;
* assertions;
* dropdown selection;
* checkbox and radio actions;
* file upload;
* screenshots;
* failure mapping;
* deterministic driver cleanup;
* and OrangeHRM qualification.

## AS-032 — Appium Mobile Engine Plugin

Support Android and later iOS automation.

Potential capabilities:

* Appium session lifecycle;
* emulator and physical-device identity;
* device capabilities;
* Android application installation;
* application launch;
* mobile selectors;
* tap;
* input;
* swipe;
* wait;
* assertions;
* screenshots;
* logs;
* session cleanup;
* device reservation;
* and mobile-runner compatibility.

## AS-033 — Cypress Engine Integration

Support Cypress through a controlled process-based plugin.

Potential capabilities:

* Node runtime compatibility;
* dependency preparation;
* Cypress configuration;
* browser selection;
* test-spec discovery;
* tags or suite filters;
* environment and secret-reference injection;
* process isolation;
* screenshots;
* videos;
* Cypress reports;
* exit-code normalization;
* and deterministic process cleanup.

---

# 8. Epic 4 — Performance and Custom Framework Engines

## Epic objective

Support non-browser and user-provided automation frameworks.

## AS-034 — Performance Engine Integration

Provide an initial performance-engine contract with JMeter or k6 as the first implementation.

Potential capabilities:

* load profiles;
* virtual users;
* duration;
* ramp-up and ramp-down;
* thresholds;
* target restrictions;
* secret references;
* result aggregation;
* percentile metrics;
* raw-result artifacts;
* execution timeouts;
* resource limits;
* and safe cancellation.

The first supported tool must be selected through an ADR.

## AS-035 — Pytest and Custom Framework Integration

Provide a controlled generic execution model for supported custom automation.

Potential capabilities:

* approved runtime definitions;
* Python/Pytest execution;
* command templates rather than arbitrary commands;
* dependency preparation;
* test discovery;
* environment injection;
* secret references;
* process limits;
* result adapters;
* JUnit XML import;
* artifacts;
* exit-code normalization;
* and secure process cleanup.

Arbitrary unrestricted shell execution remains prohibited.

---

# 9. Epic 5 — Frontend Platform

## Epic objective

Provide a production-quality Next.js user experience for platform operations.

The frontend may begin in parallel with backend work after its API dependencies are sufficiently stable.

## AS-036 — Next.js Frontend Foundation and Design System

Potential capabilities:

* Next.js application foundation;
* TypeScript;
* application routing;
* API client;
* design tokens;
* reusable components;
* forms;
* validation;
* loading states;
* error states;
* notifications;
* responsive layout;
* accessibility baseline;
* and frontend testing.

## AS-037 — Workspace and Project Screens

Potential capabilities:

* workspace listing;
* workspace creation;
* workspace update;
* project listing;
* project creation;
* project update;
* project status;
* pagination;
* filtering;
* empty states;
* and permission-aware controls.

## AS-038 — Automation Suite and Test Case Screens

Potential capabilities:

* suite listing;
* suite creation and editing;
* suite status;
* test-case listing;
* test-case creation and editing;
* engine assignment;
* source configuration;
* manifest configuration;
* ordering;
* filtering;
* and validation feedback.

## AS-039 — Environment Configuration Screen

Potential capabilities:

* environment listing;
* environment types;
* default environment;
* public configuration;
* secret-reference configuration;
* protected value display;
* validation;
* environment status;
* and execution usage warnings.

## AS-040 — Execution Launch Screen

Potential capabilities:

* project selection;
* suite selection;
* environment selection;
* execution options;
* engine information;
* immutable snapshot preview;
* validation;
* execution submission;
* and execution identity display.

## AS-041 — Live Execution Monitor

Potential capabilities:

* queued state;
* scheduled state;
* runner assignment;
* claim state;
* source preparation;
* engine execution;
* cancellation;
* terminal status;
* timestamps;
* progress events;
* logs;
* and reconnect-safe updates.

The transport mechanism must be selected through an ADR.

## AS-042 — Results, Reports and Artifact Viewer

Potential capabilities:

* execution summary;
* scenario and test results;
* step results;
* durations;
* failures;
* normalized error codes;
* screenshots;
* reports;
* logs;
* downloads;
* artifact previews;
* retention information;
* and secret-safe presentation.

## AS-043 — Dashboard and Analytics

Potential capabilities:

* execution trends;
* pass rate;
* failure rate;
* duration trends;
* engine usage;
* project health;
* flaky-test indicators;
* runner utilization;
* recent failures;
* and configurable reporting periods.

---

# 10. Epic 6 — Scheduling, Retry and Scale

## Epic objective

Complete platform-level scheduling, retry, recurrence, and controlled concurrency capabilities.

Some scheduling foundations already exist and must be reused rather than duplicated.

## AS-044 — Scheduled and Recurring Executions

Potential capabilities:

* one-time schedules;
* recurring schedules;
* timezone-aware schedules;
* schedule enable and disable;
* next execution calculation;
* immutable execution creation per occurrence;
* missed-schedule policy;
* duplicate prevention;
* and schedule audit history.

## AS-045 — Retry Policy and Execution Attempts

Potential capabilities:

* maximum attempt count;
* retryable failure classification;
* non-retryable failure classification;
* retry delay;
* exponential backoff;
* attempt identity;
* attempt history;
* execution-level terminal result;
* secret and snapshot consistency;
* and cancellation during retry waiting.

Retries remain platform-controlled and must not be implemented independently by engines.

## AS-046 — Parallel Execution and Capacity Controls

Potential capabilities:

* suite-level concurrency;
* test-level concurrency where supported;
* runner capacity;
* workspace isolation;
* browser isolation;
* execution quotas;
* per-project concurrency;
* global concurrency;
* fair scheduling;
* and deterministic cleanup.

## AS-047 — Execution Priority and Queue Governance

Potential capabilities:

* execution priority;
* FIFO within priority;
* workspace fairness;
* starvation prevention;
* queue limits;
* admission control;
* and operational queue visibility.

---

# 11. Epic 7 — Authentication, Authorization and Security

## Epic objective

Secure user access, data boundaries, secrets, and security-relevant actions.

## AS-048 — OIDC Authentication

Potential capabilities:

* OIDC login;
* user identity;
* token validation;
* session management;
* logout;
* provider-neutral configuration;
* and secure authentication failures.

## AS-049 — Workspace Authorization and Roles

Potential capabilities:

* workspace membership;
* workspace owner;
* administrator;
* contributor;
* viewer;
* resource-level authorization;
* cross-workspace isolation;
* and authorization tests.

## AS-050 — Secret Reference Management and Secure Resolution

This feature extends the execution-scoped secret boundary into managed platform capabilities.

Potential capabilities:

* secret-reference definitions;
* provider-neutral secret providers;
* environment mapping;
* access policies;
* version references;
* rotation;
* availability checks;
* execution-scoped resolution;
* audit events;
* caching restrictions;
* zeroization where practical;
* and redaction verification.

Resolved secret values remain outside the database.

## AS-051 — Audit Events

Potential capabilities:

* login events;
* configuration changes;
* execution launch;
* cancellation;
* role changes;
* secret-reference changes;
* runner administrative actions;
* export;
* retention;
* immutable audit metadata;
* and sensitive-data exclusion.

## AS-052 — Security Hardening

Potential capabilities:

* threat-model reconciliation;
* secure headers;
* dependency scanning;
* input hardening;
* SSRF prevention;
* path traversal prevention;
* archive extraction safety;
* command-injection prevention;
* artifact content controls;
* rate limiting;
* and security test automation.

---

# 12. Epic 8 — Source Control and External Integrations

## Epic objective

Connect Automation Studio with repositories, collaboration tools, and external delivery workflows.

## AS-053 — Git Repository Integration

Potential capabilities:

* repository connection;
* branch selection;
* commit selection;
* webhook-independent polling where required;
* source credential references;
* repository validation;
* commit metadata;
* source revision capture;
* and secure clone policies.

## AS-054 — Pull Request and Commit Execution

Potential capabilities:

* pull-request execution requests;
* commit-specific execution;
* changed-file filters;
* execution status publication;
* source revision evidence;
* and duplicate-event protection.

## AS-055 — Notifications

Potential integrations:

* email;
* Slack;
* Microsoft Teams.

Potential events:

* execution completed;
* execution failed;
* scheduled run missed;
* runner unavailable;
* secret resolution failed;
* and release event.

Notification failures must not change execution results.

## AS-056 — Public API and Webhook Integration

Potential capabilities:

* API tokens or scoped machine identities;
* execution launch API;
* execution status API;
* artifact metadata API;
* inbound webhook validation;
* outbound webhooks;
* signing;
* retries;
* delivery history;
* and rate limiting.

## AS-057 — MCP Gateway for AI-Facing Operations

Potential capabilities:

* controlled MCP tools;
* workspace-scoped operations;
* read-only tools by default;
* explicit mutation permissions;
* auditability;
* execution launching;
* result retrieval;
* evidence retrieval;
* and prompt-injection boundary controls.

---

# 13. Epic 9 — AI Platform Foundation

## Epic objective

Introduce a provider-neutral, governed AI plane without transferring deterministic execution control to AI.

## AS-058 — AI Provider Gateway

Potential capabilities:

* provider-neutral API;
* provider configuration;
* model selection;
* timeouts;
* retries;
* quotas;
* token accounting;
* cost metadata;
* redaction;
* and provider-failure normalization.

## AS-059 — Prompt and Context Management

Potential capabilities:

* versioned prompts;
* prompt templates;
* context assembly;
* evidence references;
* output schemas;
* workspace isolation;
* token budgeting;
* redaction;
* evaluation fixtures;
* and prompt audit metadata.

---

# 14. Epic 10 — AI-Assisted Test Engineering

## Epic objective

Help users create and improve automation while retaining human control.

## AS-060 — AI Test Generation

Potential capabilities:

* requirements-to-test suggestions;
* test-case drafts;
* manifest drafts;
* API-test drafts;
* selector suggestions;
* validation;
* evidence citation;
* approval workflow;
* and generated-content provenance.

## AS-061 — AI Test Data Generation

Potential capabilities:

* schema-based data generation;
* boundary values;
* equivalence partitions;
* negative data;
* synthetic personal data;
* deterministic seed support;
* constraints;
* redaction;
* and export.

## AS-062 — Locator Recommendation

Potential capabilities:

* DOM evidence analysis;
* resilient locator ranking;
* accessibility-based locators;
* data-attribute recommendations;
* uniqueness checks;
* and user approval.

## AS-063 — Locator-Healing Recommendations

Potential capabilities:

* failed-locator evidence;
* candidate ranking;
* confidence;
* DOM comparison;
* safe recommendation;
* and explicit human approval.

Automatic silent locator modification remains prohibited.

## AS-064 — Commit-Impact Analysis

Potential capabilities:

* changed-file analysis;
* test-to-code associations;
* impacted suite recommendations;
* confidence;
* evidence;
* execution selection suggestions;
* and user approval.

---

# 15. Epic 11 — AI-Assisted Execution Analysis

## Epic objective

Transform execution evidence into useful, explainable engineering insights.

## AS-065 — AI Execution Summary

Potential capabilities:

* execution summary;
* failed-test summary;
* dominant failure groups;
* artifact references;
* timeline summary;
* and sanitized evidence links.

## AS-066 — Failure Analysis

Potential capabilities:

* error classification;
* log analysis;
* screenshot evidence;
* network evidence;
* similar historical failures;
* confidence;
* and evidence-grounded explanation.

## AS-067 — Root-Cause Suggestions

Potential capabilities:

* likely application defect;
* likely automation defect;
* likely environment issue;
* likely data issue;
* likely infrastructure issue;
* supporting evidence;
* uncertainty;
* and recommended investigation steps.

## AS-068 — Flaky-Test Detection

Potential capabilities:

* historical pass/fail analysis;
* retry patterns;
* intermittent failure signatures;
* environment correlation;
* runner correlation;
* confidence;
* and quarantine recommendations.

AI may recommend quarantine but must not silently quarantine tests.

## AS-069 — AI Quality Intelligence

Potential capabilities:

* risk trends;
* unstable areas;
* coverage gaps;
* repeated defect patterns;
* suite-maintenance recommendations;
* and evidence-backed quality summaries.

---

# 16. Epic 12 — Operations and Observability

## Epic objective

Make the platform supportable, measurable, and safe to operate.

## AS-070 — Structured Logging and Correlation

Potential capabilities:

* structured logs;
* request ID;
* execution ID;
* attempt ID;
* runner ID;
* workspace ID;
* correlation propagation;
* log levels;
* redaction;
* and log-volume controls.

## AS-071 — Metrics and Health Monitoring

Potential capabilities:

* application health;
* database health;
* runner health;
* queue depth;
* scheduling latency;
* claim latency;
* execution duration;
* pass/fail counts;
* cleanup failures;
* artifact volume;
* and secret-provider availability.

## AS-072 — Distributed Tracing

Potential capabilities:

* request traces;
* scheduling traces;
* runner traces;
* execution spans;
* external dependency spans;
* trace correlation;
* sampling;
* and secret-safe attributes.

## AS-073 — Operational Administration

Potential capabilities:

* runner quarantine;
* runner enable and disable;
* stuck-execution inspection;
* safe execution recovery;
* cleanup retry;
* artifact-retention inspection;
* queue visibility;
* and operational audit trails.

---

# 17. Epic 13 — Packaging, Deployment and Scalability

## Epic objective

Package and deploy Automation Studio consistently across environments.

## AS-074 — Docker Packaging

Potential capabilities:

* backend container;
* frontend container;
* runner container;
* browser dependencies;
* secure base images;
* non-root execution;
* health checks;
* reproducible builds;
* and local Docker Compose.

## AS-075 — GitHub Actions CI/CD

Potential capabilities:

* build;
* unit tests;
* integration tests;
* migration tests;
* formatting;
* dependency checks;
* container build;
* artifact publishing;
* environment promotion;
* and protected release gates.

## AS-076 — Deployment Profiles

Potential capabilities:

* local;
* development;
* integration;
* staging;
* production;
* configuration validation;
* secret-provider configuration;
* database settings;
* artifact-store settings;
* and runner profiles.

## AS-077 — Horizontal Scaling and High Availability

Potential capabilities:

* multiple API instances;
* multiple schedulers with safe coordination;
* multiple runners;
* database concurrency;
* leaderless or controlled coordination;
* idempotency;
* and failure recovery.

## AS-078 — Object Storage Integration

Potential capabilities:

* provider-neutral artifact store;
* local filesystem compatibility;
* S3-compatible storage;
* upload integrity;
* download authorization;
* retention;
* and deletion.

---

# 18. Epic 14 — Reliability, Retention and Recovery

## Epic objective

Ensure long-term platform reliability and controlled data lifecycle.

## AS-079 — Backup and Restore

Potential capabilities:

* database backup;
* artifact backup policy;
* restore procedure;
* verification;
* encryption;
* recovery objectives;
* and operational runbook.

## AS-080 — Retention and Cleanup Policies

Potential capabilities:

* execution retention;
* artifact retention;
* audit retention;
* workspace cleanup;
* orphan detection;
* scheduled deletion;
* legal hold boundary;
* and deletion evidence.

## AS-081 — Disaster Recovery and Continuity

Potential capabilities:

* recovery architecture;
* dependency restoration;
* runner recovery;
* artifact recovery;
* credential rotation;
* and recovery exercises.

## AS-082 — Reliability and Fault-Injection Verification

Potential capabilities:

* runner termination;
* database interruption;
* artifact-store interruption;
* secret-provider failure;
* browser crash;
* process timeout;
* cleanup failure;
* and recovery verification.

---

# 19. Epic 15 — Performance and Platform Qualification

## Epic objective

Prove that the platform performs safely at expected scale.

## AS-083 — Backend Performance Testing

Potential capabilities:

* API load testing;
* execution-creation load;
* scheduling load;
* claim contention;
* database profiling;
* and latency objectives.

## AS-084 — Runner Scale Testing

Potential capabilities:

* concurrent runners;
* concurrent executions;
* workspace isolation;
* engine saturation;
* resource usage;
* cleanup under load;
* and fairness.

## AS-085 — Security Qualification

Potential capabilities:

* authentication testing;
* authorization testing;
* secret-isolation testing;
* dependency review;
* container review;
* penetration-test findings;
* and remediation evidence.

## AS-086 — Production Readiness Review

Potential capabilities:

* architecture review;
* security review;
* operational review;
* performance review;
* recovery review;
* documentation review;
* residual risks;
* release checklist;
* and go/no-go approval.

---

# 20. Epic 16 — Release and Product Completion

## Epic objective

Prepare and deliver the first stable public portfolio and product release.

## AS-087 — Product Documentation

Potential capabilities:

* platform overview;
* architecture;
* setup;
* local development;
* deployment;
* user guide;
* engine plugin guide;
* API reference;
* troubleshooting;
* and security guidance.

## AS-088 — Demonstration Projects

Potential demonstrations:

* OrangeHRM Playwright;
* OrangeHRM Selenium;
* REST Assured API;
* Karate API;
* mobile automation;
* performance automation;
* and AI failure analysis.

## AS-089 — Portfolio and Showcase Experience

Potential capabilities:

* polished demo workflow;
* sample data;
* guided launch;
* screenshots;
* architecture diagrams;
* demonstration videos;
* and portfolio documentation.

## AS-090 — Automation Studio v1.0 Release

Release gate:

* core management capabilities complete;
* at least three production-quality engines;
* frontend workflows complete;
* authentication and authorization complete;
* secret management complete;
* observability complete;
* deployment automation complete;
* backup and retention documented;
* performance and security qualification complete;
* release documentation complete;
* and no unresolved release-blocking risks.

---

# 21. Deferred Post-v1 Ideas

The following capabilities remain outside the committed v1 sequence unless promoted through roadmap review:

* visual test comparison;
* accessibility testing engine;
* contract-testing engine;
* service virtualization;
* test-case import from external tools;
* Jira and Azure DevOps integration;
* cloud browser farms;
* Kubernetes-native runners;
* multi-region deployment;
* marketplace for engine plugins;
* billing and subscription;
* enterprise SSO extensions;
* custom report builder;
* natural-language execution launch;
* autonomous test maintenance;
* and autonomous code changes.

Autonomous changes remain prohibited unless a future ADR introduces explicit human approval and repository safety controls.

---

# 22. Roadmap Status Definitions

Every feature must use one of these statuses:

```text
PROPOSED
DISCOVERY
DOCUMENTATION_IN_PROGRESS
DOCUMENTATION_APPROVED
IMPLEMENTATION_IN_PROGRESS
IMPLEMENTATION_COMPLETE
INDEPENDENT_REVIEW
APPROVED
COMMITTED
PUSHED
PR_OPEN
MERGED
DEFERRED
CANCELLED
```

A feature is considered complete only after it is merged into `main`.

---

# 23. Mandatory Feature Delivery Lifecycle

Every feature must follow:

```text
Repository verification
    -> documentation discovery
    -> requirements
    -> ADR
    -> implementation plan
    -> documentation review
    -> repository checkpoint
    -> branch creation
    -> incremental implementation stories
    -> focused verification
    -> full Maven verification
    -> independent review
    -> repository checkpoint
    -> commit approval
    -> commit
    -> push approval
    -> push
    -> pull request
    -> PR approval
    -> merge to main
    -> branch deletion
    -> final roadmap reconciliation
```

No stage silently implies approval for the next stage.

---

# 24. Immediate Next Feature

The immediate next proposed feature is:

```text
AS-026 — Engine Registry and Plugin Contract
```

Its first phase is:

```text
AS-026A — Requirements and Architecture Reconciliation
```

AS-026A must inspect the current repository and determine whether the Engine Registry and plugin contract are:

* already complete;
* partially complete;
* missing formal documentation;
* or missing implementation capabilities.

No AS-026 implementation scope is approved until AS-026A is independently reviewed and accepted.

---

# 25. Roadmap Change Policy

This roadmap may evolve, but changes must be intentional.

Any change to feature identity or numbering must:

1. explain why the change is required;
2. identify affected completed and upcoming features;
3. preserve historical traceability;
4. update this roadmap;
5. update the detailed product roadmap;
6. update related implementation plans;
7. receive independent review;
8. and receive explicit repository checkpoint approval.

Completed feature numbers must never be reused.
