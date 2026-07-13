# Decision Log

> Last updated: 2026-07-13

| Date | Decision | Reason | Status |
|---|---|---|---|
| 2026-07-13 | Use Spring Boot for the control-plane API. | Supports the approved Java modular-monolith control plane and its web, persistence, validation, and operational needs. | Accepted |
| 2026-07-13 | Use Java 21. | It is the architecture constraint and configured Maven language version. | Accepted |
| 2026-07-13 | Use PostgreSQL as the authoritative metadata store. | Provides durable transactional state and supports initial job claiming and outbox delivery. | Accepted |
| 2026-07-13 | Use Flyway for schema migrations. | Provides ordered, reviewable, repeatable database evolution; the dependency is generated in AS-006. | Accepted; implementation in progress |
| 2026-07-13 | Keep AI optional and outside the normal execution path. | Test execution must continue without an AI provider, and AI output must remain advisory. | Accepted |
| 2026-07-13 | Separate the backend API from execution runners. | Isolates automation runtime concerns and protects control-plane ownership and availability. | Accepted |
| 2026-07-13 | Rename the generated Java package from `com.automationstudio.studio_api` to `com.automationstudio.api`. | Aligns the API module with repository naming and avoids an implementation-specific generated package name. | Accepted; implemented for main and test sources |

See the detailed architecture records under [`docs/adr/`](../docs/adr/) and [`docs/architecture/`](../docs/architecture/).
