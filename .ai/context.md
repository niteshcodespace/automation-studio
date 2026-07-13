# Automation Studio Context

> Last updated: 2026-07-13

## Product Vision

Automation Studio is an AI-native Quality Engineering and test automation platform for managing automation projects, environments, execution, evidence, history, and reports. The first demonstration target is running an OrangeHRM smoke suite through a Playwright Java engine and displaying the result.

## User Value

The platform provides a governed, extensible place to launch automation, observe execution, retain auditable evidence, and later request evidence-grounded AI assistance without making test execution depend on an AI provider.

## Primary Users

- Quality engineers who configure and run automation.
- Platform administrators who govern access, engines, policies, and operations.
- Engineering stakeholders who review execution history, evidence, and reports.
- Future authorized CI and MCP clients using the same governed application services.

## Current Technology Stack

| Area | Technology | Current status |
|---|---|---|
| Control-plane API | Java 21, Spring Boot 4.1.0, Maven | **In progress** under `backend/studio-api` |
| Persistence | PostgreSQL, Spring Data JPA, Flyway | **In progress**; dependencies and Compose definition exist, configuration/migration not complete |
| API/operations | Spring Web MVC, Validation, Actuator | **In progress**; dependencies exist, behavior not verified |
| Execution | Dedicated Java runner and versioned engine contract | **Planned** |
| Initial engine | Playwright Java | **Planned** |
| Web application | Next.js | **Planned** in the approved architecture |
| Local deployment | Docker Compose | **In progress**; PostgreSQL service is defined |

## Current Milestone

Sprint 1 is beginning with **AS-006 Backend Skeleton** on `feature/AS-006-backend-skeleton`. See [`current-story.md`](current-story.md).

## Important Constraints

- Keep the control plane separate from the execution plane.
- Connect engines only through a shared, versioned contract.
- Keep AI optional and outside the authoritative execution path.
- Store durable, auditable execution state, history, and artifact metadata.
- Store secrets as references and resolve values only for a scoped execution.
- Keep engine-specific dependencies out of the control-plane domain.
- Prefer a practical v0.1 modular monolith and PostgreSQL queue/outbox before additional infrastructure.

## Meaning of AI-Native

AI-native means AI has explicit domain, provider, safety, provenance, audit, and integration boundaries. AI consumes authorized, redacted, evidence-grounded execution facts and produces advisory results. Normal test execution remains available when AI is disabled, unavailable, or unsuccessful; AI cannot rewrite authoritative execution outcomes.

Detailed product and architecture context is in [`product-vision.md`](../docs/vision/product-vision.md) and [`system-architecture.md`](../docs/architecture/system-architecture.md).

