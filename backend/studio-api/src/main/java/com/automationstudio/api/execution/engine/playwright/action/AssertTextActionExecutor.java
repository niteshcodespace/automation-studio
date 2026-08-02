package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import java.time.Duration;

public final class AssertTextActionExecutor extends AbstractPlaywrightActionExecutor {
    public AssertTextActionExecutor() { super(PlaywrightActionType.ASSERT_TEXT); }

    @Override
    public PlaywrightActionOutcome execute(PlaywrightStep step, PlaywrightActionExecutionContext context) {
        requireType(step);
        String selector = context.selectorResolver().resolve(step.selector());
        String expected = context.interpolator().interpolate(step.expected());
        Duration timeout = timeout(step, context, false);
        try {
            String actual = context.runtime().textContent(selector, timeout);
            return expected.equals(actual)
                    ? success(step, context)
                    : PlaywrightActionOutcome.assertionFailed(
                            context.scenarioId(), step.id(), "TEXT_ASSERTION_FAILED");
        } catch (RuntimeException exception) {
            throw new PlaywrightActionException(
                    "ACTION_EXECUTION_FAILED", "Playwright action execution failed", exception);
        }
    }
}
