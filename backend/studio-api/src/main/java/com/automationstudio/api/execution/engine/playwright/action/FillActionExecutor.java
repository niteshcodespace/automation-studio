package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import com.automationstudio.api.execution.secret.ResolvedSecret;
import com.automationstudio.api.execution.secret.SecretResolutionException;
import java.time.Duration;

public final class FillActionExecutor extends AbstractPlaywrightActionExecutor {
    public FillActionExecutor() { super(PlaywrightActionType.FILL); }

    @Override
    public PlaywrightActionOutcome execute(PlaywrightStep step, PlaywrightActionExecutionContext context) {
        requireType(step);
        String selector = context.selectorResolver().resolve(step.selector());
        if (step.secretRef() == null) {
            String value = context.interpolator().interpolate(step.value());
            Duration timeout = timeout(step, context, false);
            return infrastructure(
                    step, context, () -> context.runtime().fill(selector, value, timeout));
        }
        Duration timeout = timeout(step, context, false);
        try (ResolvedSecret secret = resolveSecret(step, context)) {
            secret.withValue(value -> fillSensitive(context, selector, value, timeout));
            return success(step, context);
        } catch (SecretResolutionException exception) {
            throw sensitiveFailure();
        }
    }

    private ResolvedSecret resolveSecret(
            PlaywrightStep step, PlaywrightActionExecutionContext context) {
        ResolvedSecret secret;
        try {
            secret = context.sensitiveFillValueResolver().resolve(step.secretRef());
        } catch (SecretResolutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SecretResolutionException(
                    "SECRET_RESOLUTION_FAILED", "Secret resolution failed");
        }
        if (secret == null || secret.isClosed()) {
            if (secret != null) {
                secret.close();
            }
            throw new SecretResolutionException(
                    "SECRET_RESOLUTION_FAILED", "Secret resolution failed");
        }
        return secret;
    }

    private void fillSensitive(
            PlaywrightActionExecutionContext context,
            String selector,
            char[] value,
            Duration timeout) {
        try {
            context.runtime().fill(selector, new String(value), timeout);
        } catch (RuntimeException exception) {
            throw new PlaywrightActionException(
                    "ACTION_EXECUTION_FAILED", "Playwright action execution failed");
        }
    }

    private PlaywrightActionException sensitiveFailure() {
        return new PlaywrightActionException(
                "SENSITIVE_FILL_RESOLUTION_FAILED", "Sensitive fill resolution failed");
    }
}
