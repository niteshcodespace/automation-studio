package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import java.time.Duration;

public final class AssertVisibleActionExecutor extends AbstractPlaywrightActionExecutor {
    public AssertVisibleActionExecutor() { super(PlaywrightActionType.ASSERT_VISIBLE); }

    @Override
    public PlaywrightActionOutcome execute(PlaywrightStep step, PlaywrightActionExecutionContext context) {
        requireType(step);
        String selector = context.selectorResolver().resolve(step.selector());
        Duration timeout = timeout(step, context, false);
        try {
            return context.runtime().isVisible(selector, timeout)
                    ? success(step, context)
                    : PlaywrightActionOutcome.assertionFailed(
                            context.scenarioId(), step.id(), "VISIBLE_ASSERTION_FAILED");
        } catch (RuntimeException exception) {
            throw new PlaywrightActionException(
                    "ACTION_EXECUTION_FAILED", "Playwright action execution failed", exception);
        }
    }
}
