# Current Story

> Last updated: 2026-07-13

## Sprint 1 — AS-006 Backend Skeleton

**Status:** **In progress**  
**Branch:** `feature/AS-006-backend-skeleton`

## Objective

Establish a runnable, testable Spring Boot control-plane API skeleton with PostgreSQL connectivity, Flyway migration support, and an observable health endpoint.

## Completed

- Generated the Maven/Spring Boot project under `backend/studio-api`.
- Selected Java 21 and generated Web MVC, JPA, Validation, PostgreSQL, Flyway, Actuator, Lombok, and DevTools dependencies.
- Renamed the main application package and source location to `com.automationstudio.api`.
- Moved the test source to `src/test/java/com/automationstudio/api/` and changed its declared package to `com.automationstudio.api`; the package rename is structurally complete for main and test sources.
- Added a PostgreSQL service definition to the root `docker-compose.yml`.
- Investigated the reported initial Maven context-load failure: JPA and Flyway attempted initialization without datasource driver/configuration.

## In Progress

- Datasource configuration and Flyway bootstrap migration are not present.
- PostgreSQL runtime readiness, application startup, Actuator response, and test success are not verified.

## Remaining Tasks

- [x] Move `StudioApiApplicationTests.java` to `src/test/java/com/automationstudio/api/`.
- [ ] Add safe datasource configuration using externalized values; do not commit private credentials.
- [ ] Add an initial versioned Flyway migration under `src/main/resources/db/migration/`.
- [ ] Start and verify the PostgreSQL service.
- [ ] Start the application and verify successful database/Flyway initialization.
- [ ] Verify the Actuator health endpoint.
- [ ] Run Maven tests and record a successful result.
- [ ] Review and update AS-006 documentation before completion.

## Definition of Done

- The Spring Boot project builds with Java 21 and Maven.
- Package paths and declarations consistently use `com.automationstudio.api`.
- PostgreSQL connection settings are externalized and documented without exposing secrets.
- The PostgreSQL service becomes healthy.
- Flyway applies an initial migration successfully.
- The application starts successfully.
- Actuator health is reachable and reports the expected state.
- Maven tests pass.
- Documentation reflects the verified final state.

## Exact Next Recommended Action

Add safe, externalized datasource configuration for the PostgreSQL service without committing private credentials; then add the initial Flyway migration before rerunning tests.
