package com.automationstudio.api.execution.engine.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.ExecutionSecretReference;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightBrowser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightNavigationPolicy;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightSelector;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightActionRuntime;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenario;
import com.automationstudio.api.execution.secret.ExecutionSecretProvider;
import com.automationstudio.api.execution.secret.ExecutionSecretProviderRegistry;
import com.automationstudio.api.execution.secret.ExecutionSecretScope;
import com.automationstudio.api.execution.secret.ResolvedSecret;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SensitiveFillActionExecutorTest {

    private static final char[] FIRST = "first-sensitive-canary".toCharArray();
    private static final char[] SECOND = "second-sensitive-canary".toCharArray();

    @Test
    void resolvesOnlyExplicitLogicalNameAndClosesHandlePromptly() {
        RecordingRuntime runtime = new RecordingRuntime();
        AtomicReference<ResolvedSecret> handle = new AtomicReference<>();
        ExecutionSecretScope scope = scope(UUID.randomUUID(), Map.of(
                "login.first", FIRST,
                "login.second", SECOND), handle);

        PlaywrightActionOutcome outcome = new FillActionExecutor().execute(
                secretStep("login.second"), context(runtime, scope::resolve));

        assertThat(outcome.status()).isEqualTo(PlaywrightActionOutcome.Status.SUCCESS);
        assertThat(runtime.values).containsExactly(new String(SECOND));
        assertThat(handle.get().isClosed()).isTrue();
        assertThat(outcome.toString()).doesNotContain(new String(SECOND));
        assertThat(scope.toString()).doesNotContain(new String(SECOND));
    }

    @Test
    void multipleSensitiveFillsUseTheirOwnExplicitNames() {
        RecordingRuntime runtime = new RecordingRuntime();
        ExecutionSecretScope scope = scope(UUID.randomUUID(), Map.of(
                "login.first", FIRST,
                "login.second", SECOND), new AtomicReference<>());
        FillActionExecutor executor = new FillActionExecutor();
        PlaywrightActionExecutionContext context = context(runtime, scope::resolve);

        executor.execute(secretStep("login.first"), context);
        executor.execute(secretStep("login.second"), context);

        assertThat(runtime.values).containsExactly(new String(FIRST), new String(SECOND));
    }

    @Test
    void executionScopesRemainIsolated() {
        RecordingRuntime firstRuntime = new RecordingRuntime();
        RecordingRuntime secondRuntime = new RecordingRuntime();
        ExecutionSecretScope firstScope = scope(UUID.randomUUID(),
                Map.of("shared.name", FIRST), new AtomicReference<>());
        ExecutionSecretScope secondScope = scope(UUID.randomUUID(),
                Map.of("shared.name", SECOND), new AtomicReference<>());

        new FillActionExecutor().execute(
                secretStep("shared.name"), context(firstRuntime, firstScope::resolve));
        new FillActionExecutor().execute(
                secretStep("shared.name"), context(secondRuntime, secondScope::resolve));

        assertThat(firstRuntime.values).containsExactly(new String(FIRST));
        assertThat(secondRuntime.values).containsExactly(new String(SECOND));
    }

    @Test
    void unknownClosedAndUnavailableScopesFailWithFixedSanitizedError() {
        ExecutionSecretScope scope = scope(UUID.randomUUID(),
                Map.of("known.name", FIRST), new AtomicReference<>());
        assertSensitiveFailure(() -> new FillActionExecutor().execute(
                secretStep("unknown.name"), context(new RecordingRuntime(), scope::resolve)));
        scope.close();
        assertSensitiveFailure(() -> new FillActionExecutor().execute(
                secretStep("known.name"), context(new RecordingRuntime(), scope::resolve)));
        assertSensitiveFailure(() -> new FillActionExecutor().execute(
                secretStep("known.name"), context(new RecordingRuntime())));
        assertSensitiveFailure(() -> new FillActionExecutor().execute(
                secretStep("known.name"), context(
                        new RecordingRuntime(),
                        ignored -> { throw new IllegalStateException(new String(FIRST)); })));
    }

    @Test
    void runtimeFailureClosesHandleAndDoesNotLeakSecretThroughFailure() {
        AtomicReference<ResolvedSecret> handle = new AtomicReference<>();
        ExecutionSecretScope scope = scope(UUID.randomUUID(),
                Map.of("login.password", FIRST), handle);
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.failWithValue = true;

        assertThatThrownBy(() -> new FillActionExecutor().execute(
                        secretStep("login.password"), context(runtime, scope::resolve)))
                .isInstanceOfSatisfying(PlaywrightActionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ACTION_EXECUTION_FAILED");
                    assertThat(failure.getMessage()).isEqualTo("Playwright action execution failed");
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.toString()).doesNotContain(new String(FIRST));
                });
        assertThat(handle.get().isClosed()).isTrue();
    }

    @Test
    void ordinaryFillStillUsesOnlyNonSecretInterpolation() {
        RecordingRuntime runtime = new RecordingRuntime();
        PlaywrightStep ordinary = new PlaywrightStep(
                "ordinary", PlaywrightActionType.FILL, new PlaywrightSelector("#field"),
                null, "hello-${name}", null, null);

        new FillActionExecutor().execute(ordinary, context(runtime));

        assertThat(runtime.values).containsExactly("hello-Ada");
    }

    @Test
    void ordinaryFillPreservesInterpolationBeforeTimeoutValidation() {
        PlaywrightStep ordinary = org.mockito.Mockito.mock(PlaywrightStep.class);
        org.mockito.Mockito.when(ordinary.action()).thenReturn(PlaywrightActionType.FILL);
        org.mockito.Mockito.when(ordinary.selector()).thenReturn(new PlaywrightSelector("#field"));
        org.mockito.Mockito.when(ordinary.value()).thenReturn("${missing}");
        org.mockito.Mockito.when(ordinary.timeout()).thenReturn(Duration.ofMillis(1));

        assertThatThrownBy(() -> new FillActionExecutor().execute(
                        ordinary, context(new RecordingRuntime())))
                .isInstanceOfSatisfying(
                        PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("VARIABLE_UNRESOLVED"));
    }

    @Test
    void scenarioOutcomeAndMetricsContainNoSensitiveValue() {
        RecordingRuntime runtime = new RecordingRuntime();
        ExecutionSecretScope scope = scope(UUID.randomUUID(),
                Map.of("login.password", FIRST), new AtomicReference<>());
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(1, Duration.ZERO);
        PlaywrightOrderedScenarioRunner runner = new PlaywrightOrderedScenarioRunner(
                new PlaywrightActionExecutorRegistry(List.of(new FillActionExecutor())));

        PlaywrightScenarioExecutionOutcome outcome = runner.execute(
                List.of(new PlaywrightScenario(
                        "scenario", "Sensitive", List.of(secretStep("login.password")))),
                context(runtime, scope::resolve),
                metrics);

        assertThat(outcome.status()).isEqualTo(PlaywrightScenarioExecutionOutcome.Status.SUCCEEDED);
        assertThat(outcome.metrics().successfulActions()).isEqualTo(1);
        assertThat(outcome.toString()).doesNotContain(new String(FIRST));
        assertThat(outcome.metrics().toString()).doesNotContain(new String(FIRST));
    }

    private void assertSensitiveFailure(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(PlaywrightActionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SENSITIVE_FILL_RESOLUTION_FAILED");
                    assertThat(failure.getMessage()).isEqualTo("Sensitive fill resolution failed");
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.toString())
                            .doesNotContain("known.name")
                            .doesNotContain("unknown.name")
                            .doesNotContain(new String(FIRST));
                });
    }

    private PlaywrightStep secretStep(String logicalName) {
        return new PlaywrightStep(
                "sensitive", PlaywrightActionType.FILL, new PlaywrightSelector("#field"),
                null, null, logicalName, null, null);
    }

    private PlaywrightActionExecutionContext context(RecordingRuntime runtime) {
        return new PlaywrightActionExecutionContext(
                "scenario", runtime, configuration(), ignored -> "resolved-selector",
                new NonSecretVariableInterpolator(Map.of("name", "Ada")),
                new SameOriginNavigationPolicy("https://example.test/"));
    }

    private PlaywrightActionExecutionContext context(
            RecordingRuntime runtime, SensitiveFillValueResolver resolver) {
        return new PlaywrightActionExecutionContext(
                "scenario", runtime, configuration(), ignored -> "resolved-selector",
                new NonSecretVariableInterpolator(Map.of("name", "Ada")),
                new SameOriginNavigationPolicy("https://example.test/"), resolver);
    }

    private PlaywrightExecutionConfiguration configuration() {
        return new PlaywrightExecutionConfiguration(
                PlaywrightBrowser.CHROMIUM, true, Duration.ofSeconds(30), Duration.ofSeconds(30),
                1280, 720, null, PlaywrightNavigationPolicy.SAME_ORIGIN);
    }

    private ExecutionSecretScope scope(
            UUID executionId,
            Map<String, char[]> values,
            AtomicReference<ResolvedSecret> handle) {
        List<ExecutionSecretReference> references = values.keySet().stream()
                .map(name -> new ExecutionSecretReference(
                        name, Map.of("provider", "test-provider", "key", "opaque-" + name)))
                .toList();
        ExecutionSecretProvider provider = new ExecutionSecretProvider() {
            @Override public String providerId() { return "test-provider"; }
            @Override public ResolvedSecret resolve(Object reference) {
                String requested = references.stream()
                        .filter(candidate -> candidate.reference().equals(reference))
                        .map(ExecutionSecretReference::name)
                        .findFirst()
                        .orElseThrow();
                ResolvedSecret resolved = ResolvedSecret.from(values.get(requested));
                handle.set(resolved);
                return resolved;
            }
        };
        return new ExecutionSecretScope(
                executionId, references, new ExecutionSecretProviderRegistry(List.of(provider)));
    }

    private static final class RecordingRuntime implements PlaywrightActionRuntime {
        private final List<String> values = new ArrayList<>();
        private boolean failWithValue;

        @Override public void fill(String selector, String value, Duration timeout) {
            if (failWithValue) {
                throw new IllegalStateException(value);
            }
            values.add(value);
        }
        @Override public void navigate(URI uri, Duration timeout) { throw new UnsupportedOperationException(); }
        @Override public void click(String selector, Duration timeout) { throw new UnsupportedOperationException(); }
        @Override public boolean isVisible(String selector, Duration timeout) { return false; }
        @Override public String textContent(String selector, Duration timeout) { return null; }
        @Override public URI currentUri() { return URI.create("https://example.test/"); }
    }
}
