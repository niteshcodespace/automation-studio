package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;

public interface PlaywrightActionExecutor {
    String actionType();
    PlaywrightActionOutcome execute(
            PlaywrightStep step, PlaywrightActionExecutionContext context);
}
