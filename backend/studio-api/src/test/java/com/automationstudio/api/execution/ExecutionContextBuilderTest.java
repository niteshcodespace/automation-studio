package com.automationstudio.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.automationstudio.api.security.SensitiveKeyDetector;
import org.junit.jupiter.api.Test;

class ExecutionContextBuilderTest {

    private final ExecutionContextBuilder builder =
            new ExecutionContextBuilder(new SensitiveKeyDetector());

    @Test
    void buildsProviderNeutralContextWithDeterministicVariablePrecedence() {
        ExecutionContext context = builder.build(source(
                Map.of("shared", "system", "systemOnly", "system"),
                Map.of("shared", "project", "projectOnly", "project"),
                environmentSnapshot(Map.of("shared", "environment", "environmentOnly", "env")),
                suiteSnapshot(Map.of("shared", "suite", "suiteOnly", "suite")),
                requestSnapshot(Map.of("shared", "execution", "executionOnly", "execution"))));

        assertThat(context.executionId()).isEqualTo(ids().execution());
        assertThat(context.projectId()).isEqualTo(ids().project());
        assertThat(context.workspaceId()).isEqualTo(ids().workspace());
        assertThat(context.suite().engineId()).isEqualTo("playwright-java");
        assertThat(context.suite().engineVersion()).isEqualTo("1.52.0");
        assertThat(context.variables()).extractingByKey("shared")
                .satisfies(variable -> {
                    assertThat(variable.value()).isEqualTo("execution");
                    assertThat(variable.source()).isEqualTo(ExecutionVariableSource.EXECUTION);
                });
        assertThat(context.variables().keySet()).contains(
                "systemOnly", "projectOnly", "environmentOnly", "suiteOnly", "executionOnly");
        assertThat(context.metadata().timeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(context.metadata().retryPolicy()).isEqualTo(ExecutionRetryPolicy.DISABLED);
    }

    @Test
    void retainsSecretReferencesWithoutResolvingValues() {
        ExecutionContext context = builder.build(source(
                Map.of(),
                Map.of(),
                environmentSnapshot(Map.of()),
                suiteSnapshot(Map.of()),
                requestSnapshot(Map.of())));

        assertThat(context.secretReferences()).containsExactly(
                new ExecutionSecretReference(
                        "apiCredential",
                        Map.of("provider", "vault", "path", "qa/api")));
        assertThat(context.environment().configuration())
                .containsEntry("browser", "chromium")
                .doesNotContainKey("variables");
    }

    @Test
    void deeplyCopiesInputAndRejectsMutationOfReturnedCollections() {
        List<Object> nested = new ArrayList<>(List.of("first"));
        Map<String, Object> environment = new LinkedHashMap<>(
                environmentSnapshot(Map.of("nested", nested)));
        ExecutionContextSource source = source(
                Map.of(), Map.of(), environment, suiteSnapshot(Map.of()), requestSnapshot(Map.of()));

        nested.add("second");
        environment.put("name", "mutated");
        ExecutionContext context = builder.build(source);

        assertThat(context.environment().environmentName()).isEqualTo("QA");
        assertThat(context.variables().get("nested").value()).isEqualTo(List.of("first"));
        assertThatThrownBy(() -> context.variables().put(
                "new", new ExecutionVariable("new", "value", ExecutionVariableSource.EXECUTION)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) context.variables()
                .get("nested").value()).add("third"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> context.runner().runnerCapabilities().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingSnapshotsInvalidEngineDuplicateCaseAndInvalidTimeout() {
        assertThatThrownBy(() -> builder.build(source(
                Map.of(), Map.of(), null, suiteSnapshot(Map.of()), requestSnapshot(Map.of()))))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessage("Execution environment snapshot is missing");
        assertThatThrownBy(() -> builder.build(source(
                Map.of(), Map.of(), environmentSnapshot(Map.of()), null, requestSnapshot(Map.of()))))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessage("Execution suite snapshot is missing");

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("engines", Map.of("selenium-java", "4.0"));
        assertThatThrownBy(() -> builder.build(sourceWithCapabilities(
                environmentSnapshot(Map.of()), suiteSnapshot(Map.of()), requestSnapshot(Map.of()),
                capabilities)))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessageContaining("does not advertise");

        assertThatThrownBy(() -> builder.build(source(
                Map.of("Region", "one"),
                Map.of("region", "two"),
                environmentSnapshot(Map.of()),
                suiteSnapshot(Map.of()),
                requestSnapshot(Map.of()))))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessageContaining("differ only by case");

        Map<String, Object> invalidRequest = new LinkedHashMap<>(requestSnapshot(Map.of()));
        invalidRequest.put("timeout", "PT0S");
        assertThatThrownBy(() -> builder.build(source(
                Map.of(), Map.of(), environmentSnapshot(Map.of()),
                suiteSnapshot(Map.of()), invalidRequest)))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessageContaining("timeout must be positive");

        assertThatThrownBy(() -> builder.build(source(
                Map.of(), Map.of(), environmentSnapshot(Map.of("apiToken", "not-allowed")),
                suiteSnapshot(Map.of()), requestSnapshot(Map.of()))))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessageContaining("sensitive value key");
    }

    private ExecutionContextSource source(
            Map<String, Object> system,
            Map<String, Object> project,
            Map<String, Object> environment,
            Map<String, Object> suite,
            Map<String, Object> request) {
        return sourceWithCapabilities(
                environment, suite, request,
                Map.of("engines", Map.of("playwright-java", "1.52.0")),
                system, project);
    }

    private ExecutionContextSource sourceWithCapabilities(
            Map<String, Object> environment,
            Map<String, Object> suite,
            Map<String, Object> request,
            Map<String, Object> capabilities) {
        return sourceWithCapabilities(
                environment, suite, request, capabilities, Map.of(), Map.of());
    }

    private ExecutionContextSource sourceWithCapabilities(
            Map<String, Object> environment,
            Map<String, Object> suite,
            Map<String, Object> request,
            Map<String, Object> capabilities,
            Map<String, Object> system,
            Map<String, Object> project) {
        Ids ids = ids();
        return new ExecutionContextSource(
                ids.execution(),
                ids.project(),
                ids.workspace(),
                ids.environment(),
                ids.suite(),
                OffsetDateTime.parse("2026-07-27T10:00:00Z"),
                environment,
                suite,
                request,
                ids.runner(),
                "runner-1",
                "1.0.0",
                "linux",
                "amd64",
                capabilities,
                Map.of("region", "eu"),
                OffsetDateTime.parse("2026-07-27T10:01:00Z"),
                system,
                project);
    }

    private Map<String, Object> environmentSnapshot(Map<String, Object> variables) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("browser", "chromium");
        configuration.put("variables", variables);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", ids().environment().toString());
        snapshot.put("name", "QA");
        snapshot.put("type", "TEST");
        snapshot.put("baseUrl", "https://example.test");
        snapshot.put("configuration", configuration);
        snapshot.put("secretReferences", Map.of(
                "apiCredential", Map.of("provider", "vault", "path", "qa/api")));
        return snapshot;
    }

    private Map<String, Object> suiteSnapshot(Map<String, Object> variables) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("headless", true);
        configuration.put("variables", variables);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", ids().suite().toString());
        snapshot.put("name", "Smoke");
        snapshot.put("engineType", "PLAYWRIGHT");
        snapshot.put("engineId", "playwright-java");
        snapshot.put("suiteType", "WEB");
        snapshot.put("suiteReference", "tests/smoke");
        snapshot.put("configuration", configuration);
        return snapshot;
    }

    private Map<String, Object> requestSnapshot(Map<String, Object> variables) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("variables", variables);
        snapshot.put("timeout", "PT10M");
        snapshot.put("retryPolicy", "DISABLED");
        return snapshot;
    }

    private Ids ids() {
        return new Ids(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                UUID.fromString("00000000-0000-0000-0000-000000000006"));
    }

    private record Ids(
            UUID execution,
            UUID project,
            UUID workspace,
            UUID environment,
            UUID suite,
            UUID runner) {
    }
}
