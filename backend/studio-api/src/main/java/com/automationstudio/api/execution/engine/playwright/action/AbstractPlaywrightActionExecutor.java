package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import java.time.Duration;

abstract class AbstractPlaywrightActionExecutor implements PlaywrightActionExecutor {
    private final PlaywrightActionType type;

    AbstractPlaywrightActionExecutor(PlaywrightActionType type) {
        this.type = type;
    }

    @Override
    public final String actionType() {
        return type.manifestValue();
    }

    final void requireType(PlaywrightStep step) {
        if (step == null || step.action() != type) {
            throw new PlaywrightActionException("ACTION_TYPE_INVALID", "Action executor input is invalid");
        }
    }

    final Duration timeout(PlaywrightStep step, PlaywrightActionExecutionContext context, boolean navigation) {
        Duration timeout = step.timeout() != null
                ? step.timeout()
                : navigation ? context.configuration().navigationTimeout() : context.configuration().actionTimeout();
        long maximum = navigation
                ? context.configuration().MAX_NAVIGATION_TIMEOUT_MILLIS
                : context.configuration().MAX_ACTION_TIMEOUT_MILLIS;
        if (timeout == null || timeout.toMillis() < context.configuration().MIN_TIMEOUT_MILLIS
                || timeout.toMillis() > maximum) {
            throw new PlaywrightActionException("ACTION_TIMEOUT_INVALID", "Action timeout is invalid");
        }
        return timeout;
    }

    final PlaywrightActionOutcome infrastructure(
            PlaywrightStep step, PlaywrightActionExecutionContext context, ActionCall call) {
        try {
            call.run();
            return success(step, context);
        } catch (PlaywrightActionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PlaywrightActionException(
                    "ACTION_EXECUTION_FAILED", "Playwright action execution failed", exception);
        }
    }

    final PlaywrightActionOutcome success(PlaywrightStep step, PlaywrightActionExecutionContext context) {
        return PlaywrightActionOutcome.success(context.scenarioId(), step.id());
    }

    @FunctionalInterface
    interface ActionCall { void run(); }
}
