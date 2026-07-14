# Decision Log

> Last updated: 2026-07-14

| Date | Decision | Reason | Status |
|---|---|---|---|
| 2026-07-13 | Use Spring Boot for the control-plane API. | Supports the approved Java modular-monolith control plane and its web, persistence, validation, and operational needs. | Accepted |
| 2026-07-13 | Use Java 21. | It is the architecture constraint and configured Maven language version. | Accepted |
| 2026-07-13 | Use PostgreSQL as the authoritative metadata store. | Provides durable transactional state and supports initial job claiming and outbox delivery. | Accepted |
| 2026-07-13 | Use Flyway for schema migrations. | Provides ordered, reviewable, repeatable database evolution; the dependency is generated in AS-006. | Accepted; implemented in AS-006 |
| 2026-07-13 | Keep AI optional and outside the normal execution path. | Test execution must continue without an AI provider, and AI output must remain advisory. | Accepted |
| 2026-07-13 | Separate the backend API from execution runners. | Isolates automation runtime concerns and protects control-plane ownership and availability. | Accepted |
| 2026-07-13 | Rename the generated Java package from `com.automationstudio.studio_api` to `com.automationstudio.api`. | Aligns the API module with repository naming and avoids an implementation-specific generated package name. | Accepted; implemented for main and test sources |
| 2026-07-14 | Use UUID identifiers for the core execution schema. | AS-007 implements and verifies UUID identifiers across the schema. | Accepted; implemented in AS-007 |
| 2026-07-14 | Use optimistic locking for executions through the `execution.version` column. | AS-007 implements and verifies the version column on `execution`. | Accepted; implemented in AS-007 |
| 2026-07-14 | Cascade deletion from `execution` to `execution_artifact`, while restricting execution relationship deletion from `project`, `environment`, and `test_suite`. | AS-007 verifies `ON DELETE CASCADE` for execution artifacts and `ON DELETE RESTRICT` for the other execution relationships. | Accepted; implemented in AS-007 |
| 2026-07-14 | Map the Flyway V2 execution schema with JPA entities and Spring Data repository interfaces. | Keeps persistence mappings aligned with the versioned database contract while preserving separate controller, service, domain, entity, and repository boundaries. | Accepted; implemented and validated in AS-008 |

See the detailed architecture records under [`docs/adr/`](../docs/adr/) and [`docs/architecture/`](../docs/architecture/).
