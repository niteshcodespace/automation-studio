package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightActionRuntime;
import com.automationstudio.api.execution.secret.SecretResolutionException;
import java.util.Objects;

public record PlaywrightActionExecutionContext(
        String scenarioId,
        PlaywrightActionRuntime runtime,
        PlaywrightExecutionConfiguration configuration,
        SelectorResolver selectorResolver,
        NonSecretVariableInterpolator interpolator,
        SameOriginNavigationPolicy navigationPolicy,
        SensitiveFillValueResolver sensitiveFillValueResolver) {

    public PlaywrightActionExecutionContext {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("Scenario id is required");
        }
        runtime = Objects.requireNonNull(runtime, "Action runtime is required");
        configuration = Objects.requireNonNull(configuration, "Configuration is required");
        selectorResolver = Objects.requireNonNull(selectorResolver, "Selector resolver is required");
        interpolator = Objects.requireNonNull(interpolator, "Variable interpolator is required");
        navigationPolicy = Objects.requireNonNull(navigationPolicy, "Navigation policy is required");
        sensitiveFillValueResolver = Objects.requireNonNull(
                sensitiveFillValueResolver, "Sensitive fill resolver is required");
    }

    public PlaywrightActionExecutionContext(
            String scenarioId,
            PlaywrightActionRuntime runtime,
            PlaywrightExecutionConfiguration configuration,
            SelectorResolver selectorResolver,
            NonSecretVariableInterpolator interpolator,
            SameOriginNavigationPolicy navigationPolicy) {
        this(
                scenarioId,
                runtime,
                configuration,
                selectorResolver,
                interpolator,
                navigationPolicy,
                ignored -> { throw new SecretResolutionException(
                        "SECRET_CAPABILITY_UNAVAILABLE", "Secret capability is unavailable"); });
    }

    PlaywrightActionExecutionContext forScenario(String newScenarioId) {
        return new PlaywrightActionExecutionContext(
                newScenarioId,
                runtime,
                configuration,
                selectorResolver,
                interpolator,
                navigationPolicy,
                sensitiveFillValueResolver);
    }
}
