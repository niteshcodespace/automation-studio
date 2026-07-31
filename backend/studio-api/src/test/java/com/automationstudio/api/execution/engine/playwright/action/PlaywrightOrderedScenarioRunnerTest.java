package com.automationstudio.api.execution.engine.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenario;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightSelector;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaywrightOrderedScenarioRunnerTest {
    @Test
    void executesInExactOrderAndFreezesSuccessfulMetrics() {
        List<String> order = new ArrayList<>();
        PlaywrightScenario scenario = scenario();
        PlaywrightActionExecutorRegistry registry = registry(order, null, false);
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(3, Duration.ofNanos(7));
        PlaywrightOrderedScenarioRunner runner =
                new PlaywrightOrderedScenarioRunner(registry, ticks(10, 60));

        PlaywrightScenarioExecutionOutcome outcome =
                runner.execute(List.of(scenario), nullSafeContext(), metrics);

        assertThat(order).containsExactly("a", "b", "c");
        assertThat(outcome.status()).isEqualTo(PlaywrightScenarioExecutionOutcome.Status.SUCCEEDED);
        assertThat(outcome.metrics()).isEqualTo(
                new PlaywrightRuntimeMetrics(3, 3, 0, Duration.ofNanos(50), Duration.ofNanos(7)));
    }

    @Test
    void stopsAtAssertionFailureAndDoesNotCountLaterStep() {
        List<String> order = new ArrayList<>();
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(3, Duration.ZERO);
        PlaywrightScenarioExecutionOutcome outcome = new PlaywrightOrderedScenarioRunner(
                        registry(order, "b", false), ticks(1, 1))
                .execute(List.of(scenario()), nullSafeContext(), metrics);

        assertThat(order).containsExactly("a", "b");
        assertThat(outcome.status())
                .isEqualTo(PlaywrightScenarioExecutionOutcome.Status.ASSERTION_FAILED);
        assertThat(outcome.metrics().totalActions()).isEqualTo(3);
        assertThat(outcome.metrics().successfulActions()).isEqualTo(1);
        assertThat(outcome.metrics().failedActions()).isEqualTo(1);
    }

    @Test
    void stopsAtInfrastructureFailureAndAccumulatorRetainsFailureMetrics() {
        List<String> order = new ArrayList<>();
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(3, Duration.ZERO);

        assertThatThrownBy(() -> new PlaywrightOrderedScenarioRunner(
                        registry(order, "b", true), ticks(1, 2))
                .execute(List.of(scenario()), nullSafeContext(), metrics))
                .isInstanceOf(PlaywrightActionException.class);
        assertThat(order).containsExactly("a", "b");
        assertThat(metrics.freeze())
                .isEqualTo(new PlaywrightRuntimeMetrics(
                        3, 1, 1, Duration.ofNanos(1), Duration.ZERO));
    }

    @Test
    void rejectsMismatchedPlannedCountBeforeExecution() {
        List<String> order = new ArrayList<>();
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(2, Duration.ZERO);

        assertThatThrownBy(() -> new PlaywrightOrderedScenarioRunner(
                        registry(order, null, false), ticks(1, 2))
                .execute(List.of(scenario()), nullSafeContext(), metrics))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACTION_METRICS_INVALID"));
        assertThat(order).isEmpty();
    }

    @Test
    void preservesInfrastructureFailureWhenTimingAlsoFails() {
        List<String> order = new ArrayList<>();
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(3, Duration.ZERO);

        assertThatThrownBy(() -> new PlaywrightOrderedScenarioRunner(
                        registry(order, "b", true), ticks(2, 1))
                .execute(List.of(scenario()), nullSafeContext(), metrics))
                .isInstanceOfSatisfying(PlaywrightActionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ACTION_EXECUTION_FAILED");
                    assertThat(failure.getSuppressed()).hasSize(1);
                });
    }

    @Test
    void executesMultipleScenariosInExactGlobalOrderWithOneMetricResult() {
        List<String> order = new ArrayList<>();
        List<PlaywrightScenario> scenarios = List.of(scenario(), scenario("scenario-2", "2"));
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(6, Duration.ofNanos(3));

        PlaywrightScenarioExecutionOutcome outcome = new PlaywrightOrderedScenarioRunner(
                        registry(order, null, false), ticks(10, 90))
                .execute(scenarios, nullSafeContext(), metrics);

        assertThat(order).containsExactly("a", "b", "c", "a2", "b2", "c2");
        assertThat(outcome.status()).isEqualTo(PlaywrightScenarioExecutionOutcome.Status.SUCCEEDED);
        assertThat(outcome.metrics()).isEqualTo(
                new PlaywrightRuntimeMetrics(6, 6, 0, Duration.ofNanos(80), Duration.ofNanos(3)));
    }

    @Test
    void assertionFailureStopsAllLaterStepsAndScenarios() {
        List<String> order = new ArrayList<>();
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(6, Duration.ZERO);

        PlaywrightScenarioExecutionOutcome outcome = new PlaywrightOrderedScenarioRunner(
                        registry(order, "b", false), ticks(1, 2))
                .execute(List.of(scenario(), scenario("scenario-2", "2")), nullSafeContext(), metrics);

        assertThat(order).containsExactly("a", "b");
        assertThat(outcome.metrics().successfulActions()).isEqualTo(1);
        assertThat(outcome.metrics().failedActions()).isEqualTo(1);
        assertThat(outcome.metrics().totalActions()).isEqualTo(6);
    }

    @Test
    void laterScenarioAssertionStopsItsRemainingSteps() {
        List<String> order = new ArrayList<>();
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(6, Duration.ZERO);

        PlaywrightScenarioExecutionOutcome outcome = new PlaywrightOrderedScenarioRunner(
                        registry(order, "b2", false), ticks(1, 2))
                .execute(List.of(scenario(), scenario("scenario-2", "2")), nullSafeContext(), metrics);

        assertThat(order).containsExactly("a", "b", "c", "a2", "b2");
        assertThat(outcome.metrics().successfulActions()).isEqualTo(4);
        assertThat(outcome.metrics().failedActions()).isEqualTo(1);
    }

    @Test
    void missingExecutorRecordsOneFailureAndStopsGlobally() {
        List<String> order = new ArrayList<>();
        PlaywrightActionExecutorRegistry registry = new PlaywrightActionExecutorRegistry(
                List.of(executor("navigate", "ignored", order, null, false)));
        PlaywrightActionMetricsAccumulator metrics =
                new PlaywrightActionMetricsAccumulator(6, Duration.ZERO);

        assertThatThrownBy(() -> new PlaywrightOrderedScenarioRunner(registry, ticks(1, 2))
                .execute(List.of(scenario(), scenario("scenario-2", "2")), nullSafeContext(), metrics))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("UNSUPPORTED_ACTION"));
        assertThat(order).containsExactly("a");
        assertThat(metrics.freeze())
                .isEqualTo(new PlaywrightRuntimeMetrics(6, 1, 1, Duration.ofNanos(1), Duration.ZERO));
    }

    @Test
    void rejectsEmptyScenarioCollectionBeforeTimingOrExecution() {
        assertThatThrownBy(() -> new PlaywrightOrderedScenarioRunner(
                        new PlaywrightActionExecutorRegistry(List.of()), ticks(1, 2))
                .execute(List.of(), nullSafeContext(),
                        new PlaywrightActionMetricsAccumulator(0, Duration.ZERO)))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACTION_EXECUTION_INVALID"));
    }

    private PlaywrightActionExecutorRegistry registry(
            List<String> order, String failAt, boolean infrastructure) {
        return new PlaywrightActionExecutorRegistry(List.of(
                executor("navigate", "a", order, failAt, infrastructure),
                executor("click", "b", order, failAt, infrastructure),
                executor("fill", "c", order, failAt, infrastructure)));
    }

    private PlaywrightActionExecutor executor(
            String type, String marker, List<String> order, String failAt, boolean infrastructure) {
        return new PlaywrightActionExecutor() {
            @Override public String actionType() { return type; }
            @Override public PlaywrightActionOutcome execute(
                    PlaywrightStep step, PlaywrightActionExecutionContext context) {
                order.add(step.id());
                if (step.id().equals(failAt)) {
                    if (infrastructure) throw new PlaywrightActionException("ACTION_EXECUTION_FAILED", "failed");
                    return PlaywrightActionOutcome.assertionFailed("scenario", step.id(), "ASSERTION_FAILED");
                }
                return PlaywrightActionOutcome.success("scenario", step.id());
            }
        };
    }

    private PlaywrightScenario scenario() {
        return scenario("scenario", "");
    }

    private PlaywrightScenario scenario(String id, String suffix) {
        return new PlaywrightScenario(id, "Scenario", List.of(
                new PlaywrightStep("a" + suffix, PlaywrightActionType.NAVIGATE, null, "/", null, null, null),
                new PlaywrightStep("b" + suffix, PlaywrightActionType.CLICK,
                        new PlaywrightSelector("#b"), null, null, null, null),
                new PlaywrightStep("c" + suffix, PlaywrightActionType.FILL,
                        new PlaywrightSelector("#c"), null, "x", null, null)));
    }

    private PlaywrightActionExecutionContext nullSafeContext() {
        return org.mockito.Mockito.mock(PlaywrightActionExecutionContext.class);
    }

    private ActionMonotonicTicker ticks(long first, long second) {
        long[] values = {first, second};
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        return () -> values[index.getAndIncrement()];
    }
}
