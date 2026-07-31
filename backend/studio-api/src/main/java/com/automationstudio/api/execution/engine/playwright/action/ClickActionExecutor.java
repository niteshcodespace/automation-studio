package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import java.time.Duration;

public final class ClickActionExecutor extends AbstractPlaywrightActionExecutor {
    public ClickActionExecutor() { super(PlaywrightActionType.CLICK); }

    @Override
    public PlaywrightActionOutcome execute(PlaywrightStep step, PlaywrightActionExecutionContext context) {
        requireType(step);
        String selector = context.selectorResolver().resolve(step.selector());
        Duration timeout = timeout(step, context, false);
        return infrastructure(step, context, () -> context.runtime().click(selector, timeout));
    }
}
