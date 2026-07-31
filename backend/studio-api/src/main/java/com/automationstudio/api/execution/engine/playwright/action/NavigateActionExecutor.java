package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import java.net.URI;
import java.time.Duration;

public final class NavigateActionExecutor extends AbstractPlaywrightActionExecutor {
    public NavigateActionExecutor() { super(PlaywrightActionType.NAVIGATE); }

    @Override
    public PlaywrightActionOutcome execute(PlaywrightStep step, PlaywrightActionExecutionContext context) {
        requireType(step);
        String target = context.interpolator().interpolate(step.url());
        URI validated = context.navigationPolicy().resolve(target);
        Duration timeout = timeout(step, context, true);
        return infrastructure(step, context, () -> {
            context.runtime().navigate(validated, timeout);
            context.navigationPolicy().validateFinal(context.runtime().currentUri());
        });
    }
}
