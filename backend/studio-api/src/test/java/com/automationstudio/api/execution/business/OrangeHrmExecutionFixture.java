package com.automationstudio.api.execution.business;

import com.automationstudio.api.execution.ExecutionContextSource;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

final class OrangeHrmExecutionFixture {

    static final String SUITE_REFERENCE =
            "demo-projects/orangehrm-login-smoke/scenario.json";
    static final String ENGINE_NAME = "playwright-java";
    static final String ENGINE_VERSION = "1.61.0";
    static final String BASE_URL = "https://orangehrm.invalid";
    static final String USERNAME_SECRET = "orangehrm.username";
    static final String PASSWORD_SECRET = "orangehrm.password";
    static final String SOURCE_REVISION = "025e025e025e025e025e025e025e025e025e025e";

    private static final UUID EXECUTION_ID =
            UUID.fromString("025e0000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("025e0000-0000-4000-8000-000000000002");
    private static final UUID WORKSPACE_ID =
            UUID.fromString("025e0000-0000-4000-8000-000000000003");
    private static final UUID ENVIRONMENT_ID =
            UUID.fromString("025e0000-0000-4000-8000-000000000004");
    private static final UUID SUITE_ID =
            UUID.fromString("025e0000-0000-4000-8000-000000000005");
    private static final UUID RUNNER_ID =
            UUID.fromString("025e0000-0000-4000-8000-000000000006");

    private OrangeHrmExecutionFixture() { }

    static ExecutionSourceReference sourceReference() {
        return new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                "https://source.invalid/automation-studio.git",
                SOURCE_REVISION,
                "demo-projects/orangehrm-login-smoke");
    }

    static ExecutionContextSource contextSource() {
        return new ExecutionContextSource(
                EXECUTION_ID,
                PROJECT_ID,
                WORKSPACE_ID,
                ENVIRONMENT_ID,
                SUITE_ID,
                OffsetDateTime.parse("2026-08-03T09:00:00Z"),
                environmentSnapshot(),
                suiteSnapshot(),
                requestSnapshot(),
                RUNNER_ID,
                "as025e-runner",
                "0.1.0",
                "linux",
                "amd64",
                Map.of("engines", Map.of(ENGINE_NAME, ENGINE_VERSION)),
                Map.of("fixture", "as025e"),
                OffsetDateTime.parse("2026-08-03T09:01:00Z"),
                Map.of(),
                Map.of());
    }

    private static Map<String, Object> environmentSnapshot() {
        return Map.of(
                "id", ENVIRONMENT_ID.toString(),
                "name", "AS-025E deterministic target",
                "type", "TEST",
                "baseUrl", BASE_URL,
                "configuration", Map.of("variables", Map.of()),
                "secretReferences", Map.of(
                        USERNAME_SECRET,
                        Map.of(
                                "provider", "operator-environment",
                                "key", "AS025E_ORANGEHRM_USERNAME"),
                        PASSWORD_SECRET,
                        Map.of(
                                "provider", "operator-environment",
                                "key", "AS025E_ORANGEHRM_PASSWORD")));
    }

    private static Map<String, Object> suiteSnapshot() {
        return Map.of(
                "id", SUITE_ID.toString(),
                "name", "OrangeHRM login smoke",
                "engineType", "PLAYWRIGHT",
                "engineId", ENGINE_NAME,
                "suiteType", "WEB",
                "suiteReference", SUITE_REFERENCE,
                "configuration", Map.of("variables", Map.of()));
    }

    private static Map<String, Object> requestSnapshot() {
        return Map.of(
                "variables", Map.of("baseUrl", BASE_URL),
                "timeout", "PT2M",
                "retryPolicy", "DISABLED");
    }
}
