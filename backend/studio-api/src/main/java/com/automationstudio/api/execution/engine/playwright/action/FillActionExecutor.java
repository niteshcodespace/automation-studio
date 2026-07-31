package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import java.time.Duration;

public final class FillActionExecutor extends AbstractPlaywrightActionExecutor {
    public FillActionExecutor() { super(PlaywrightActionType.FILL); }

    @Override
    public PlaywrightActionOutcome execute(PlaywrightStep step, PlaywrightActionExecutionContext context) {
        requireType(step);
        String selector = context.selectorResolver().resolve(step.selector());
        String value = context.interpolator().interpolate(step.value());
        Duration timeout = timeout(step, context, false);
        return infrastructure(step, context, () -> context.runtime().fill(selector, value, timeout));
    }
}
