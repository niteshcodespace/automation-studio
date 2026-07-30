package com.automationstudio.api.execution.engine.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.ExecutionSecretReference;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistryImpl;
import com.automationstudio.api.execution.lifecycle.ExecutionFailureReason;
import com.automationstudio.api.execution.lifecycle.ExecutionStatus;
import com.automationstudio.api.security.SensitiveKeyDetector;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BuiltinExecutionEngineTest {

    private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
    private final UUID executionId = UUID.randomUUID();
    private final UUID runnerId = UUID.randomUUID();
    private BuiltinExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new BuiltinExecutionEngine(
                new BuiltinExecutionEngineConfiguration(new SensitiveKeyDetector()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exposesImmutableDescriptorAndRegistersByExactIdentity() {
        assertThat(engine.descriptor().engineName()).isEqualTo("BUILTIN");
        assertThat(engine.descriptor().engineVersion()).isEqualTo("1.0.0");
        assertThat(engine.descriptor().displayName())
                .isEqualTo("Built-in Deterministic Engine");
        assertThatThrownBy(() -> engine.descriptor().supportedFeatures().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        var registry = new ExecutionEngineRegistryImpl(List.of(engine));
        assertThat(registry.resolve("BUILTIN", "1.0.0").engine()).isSameAs(engine);
    }

    @Test
    void returnsSuccessfulSanitizedResultWithValidEvidence() {
        ExecutionContext context = context(Map.of(
                "operation", "SUCCEED",
                "message", "approved",
                "evidence", Map.of("enabled", true)));

        var result = engine.execute(context);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.executionId()).isEqualTo(executionId);
        assertThat(result.runnerId()).isEqualTo(runnerId);
        assertThat(result.duration()).isZero();
        assertThat(result.metadata()).containsOnly(
                org.assertj.core.data.MapEntry.entry("engine", "BUILTIN"),
                org.assertj.core.data.MapEntry.entry("engineVersion", "1.0.0"),
                org.assertj.core.data.MapEntry.entry("operation", "SUCCEED"),
                org.assertj.core.data.MapEntry.entry("message", "approved"));
        assertThat(result.evidence().artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.reference().uri().getScheme()).isEqualTo("builtin");
            assertThat(artifact.reference().uri().getUserInfo()).isNull();
            assertThat(artifact.size()).isZero();
        });
        assertThat(result.evidence().summary().duration()).isEqualTo(result.duration());
    }

    @Test
    void returnsProviderDeclaredFailureWithoutEvidenceOrSecretLeakage() {
        ExecutionContext context = context(Map.of(
                "operation", "FAIL",
                "message", "safe failure"));

        var result = engine.execute(context);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.failureReason())
                .isEqualTo(ExecutionFailureReason.ENGINE_REPORTED_FAILURE);
        assertThat(result.evidence().artifacts()).isEmpty();
        assertThat(result.evidence().summary().errorCount()).isOne();
        assertThat(result.toString())
                .doesNotContain("claim-token", "secret-reference", "provider stack");
    }

    @Test
    void validatesBeforeExecutionAndDoesNotMutateContextConfiguration() {
        Map<String, Object> nestedEvidence = new java.util.LinkedHashMap<>();
        nestedEvidence.put("enabled", true);
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("operation", "SUCCEED");
        input.put("evidence", nestedEvidence);
        ExecutionContext context = context(input);

        assertThatCode(() -> engine.validate(context)).doesNotThrowAnyException();
        engine.execute(context);

        assertThat(input).containsEntry("operation", "SUCCEED");
        assertThat(nestedEvidence).containsEntry("enabled", true);
    }

    @Test
    void executesDeterministicallyAndSafelyForConcurrentCallers() throws Exception {
        ExecutionContext context = context(Map.of(
                "operation", "SUCCEED",
                "evidence", Map.of("enabled", true)));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(ignored -> (java.util.concurrent.Callable<
                            com.automationstudio.api.execution.lifecycle.ExecutionResult>)
                            () -> engine.execute(context))
                    .toList();
            var results = executor.invokeAll(calls).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(results).allMatch(result ->
                    result.status() == ExecutionStatus.SUCCEEDED);
            assertThat(results)
                    .extracting(result -> result.evidence().artifacts().getFirst().artifactId())
                    .containsOnly(results.getFirst().evidence().artifacts().getFirst().artifactId());
        }
    }

    private ExecutionContext context(Map<String, Object> configuration) {
        ExecutionContext context = mock(ExecutionContext.class);
        ExecutionSuiteSnapshot suite = mock(ExecutionSuiteSnapshot.class);
        ExecutionRunnerContext runner = mock(ExecutionRunnerContext.class);
        when(context.executionId()).thenReturn(executionId);
        when(context.suite()).thenReturn(suite);
        when(context.runner()).thenReturn(runner);
        when(context.secretReferences()).thenReturn(List.of(
                mock(ExecutionSecretReference.class)));
        when(suite.configuration()).thenReturn(configuration);
        when(runner.runnerId()).thenReturn(runnerId);
        return context;
    }
}
