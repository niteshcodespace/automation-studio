package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenario;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class PlaywrightOrderedScenarioRunner {
    private final PlaywrightActionExecutorRegistry registry;
    private final ActionMonotonicTicker ticker;

    public PlaywrightOrderedScenarioRunner(PlaywrightActionExecutorRegistry registry) {
        this(registry, System::nanoTime);
    }

    PlaywrightOrderedScenarioRunner(
            PlaywrightActionExecutorRegistry registry, ActionMonotonicTicker ticker) {
        this.registry = Objects.requireNonNull(registry, "Action registry is required");
        this.ticker = Objects.requireNonNull(ticker, "Action ticker is required");
    }

    public PlaywrightScenarioExecutionOutcome execute(
            List<PlaywrightScenario> scenarios,
            PlaywrightActionExecutionContext context,
            PlaywrightActionMetricsAccumulator metrics) {
        if (scenarios == null || scenarios.isEmpty() || scenarios.stream().anyMatch(Objects::isNull)) {
            throw new PlaywrightActionException(
                    "ACTION_EXECUTION_INVALID", "Action execution input is invalid");
        }
        Objects.requireNonNull(context, "Action context is required");
        Objects.requireNonNull(metrics, "Metrics accumulator is required");
        metrics.requirePlannedActions(plannedActions(scenarios));
        long started = ticker.readNanos();
        PlaywrightActionOutcome terminalAction = null;
        try {
            execution:
            for (PlaywrightScenario scenario : scenarios) {
                PlaywrightActionExecutionContext scenarioContext = context.forScenario(scenario.id());
                for (PlaywrightStep step : scenario.steps()) {
                    PlaywrightActionOutcome actionOutcome;
                    try {
                        PlaywrightActionExecutor executor = registry.resolve(step.action());
                        actionOutcome = executor.execute(step, scenarioContext);
                        if (actionOutcome == null) {
                            throw new PlaywrightActionException(
                                    "ACTION_OUTCOME_INVALID", "Action execution outcome is invalid");
                        }
                    } catch (RuntimeException exception) {
                        metrics.recordFailure();
                        throw exception;
                    }
                    if (actionOutcome.status() == PlaywrightActionOutcome.Status.ASSERTION_FAILED) {
                        metrics.recordFailure();
                        terminalAction = actionOutcome;
                        break execution;
                    }
                    metrics.recordSuccess();
                }
            }
        } catch (RuntimeException exception) {
            try {
                setDuration(metrics, started);
            } catch (RuntimeException timingFailure) {
                exception.addSuppressed(timingFailure);
            }
            throw exception;
        }
        setDuration(metrics, started);
        return new PlaywrightScenarioExecutionOutcome(
                terminalAction == null
                        ? PlaywrightScenarioExecutionOutcome.Status.SUCCEEDED
                        : PlaywrightScenarioExecutionOutcome.Status.ASSERTION_FAILED,
                terminalAction,
                metrics.freeze());
    }

    private long plannedActions(List<PlaywrightScenario> scenarios) {
        long total = 0;
        try {
            for (PlaywrightScenario scenario : scenarios) {
                total = Math.addExact(total, scenario.steps().size());
            }
        } catch (ArithmeticException exception) {
            throw new PlaywrightActionException(
                    "ACTION_METRICS_INVALID", "Action metrics input is invalid", exception);
        }
        return total;
    }

    private void setDuration(PlaywrightActionMetricsAccumulator metrics, long started) {
        long elapsed = ticker.readNanos() - started;
        if (elapsed < 0) {
            throw new PlaywrightActionException("ACTION_TIMING_INVALID", "Action timing is invalid");
        }
        metrics.executionDuration(Duration.ofNanos(elapsed));
    }
}
