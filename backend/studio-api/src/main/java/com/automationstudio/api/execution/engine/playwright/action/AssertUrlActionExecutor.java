package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import java.net.URI;

public final class AssertUrlActionExecutor extends AbstractPlaywrightActionExecutor {
    public AssertUrlActionExecutor() { super(PlaywrightActionType.ASSERT_URL); }

    @Override
    public PlaywrightActionOutcome execute(PlaywrightStep step, PlaywrightActionExecutionContext context) {
        requireType(step);
        URI expected = context.navigationPolicy().resolve(
                context.interpolator().interpolate(step.expected()));
        try {
            URI actual = context.navigationPolicy().validateFinal(context.runtime().currentUri());
            return expected.equals(actual)
                    ? success(step, context)
                    : PlaywrightActionOutcome.assertionFailed(
                            context.scenarioId(), step.id(), "URL_ASSERTION_FAILED");
        } catch (PlaywrightActionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PlaywrightActionException(
                    "ACTION_EXECUTION_FAILED", "Playwright action execution failed", exception);
        }
    }
}
