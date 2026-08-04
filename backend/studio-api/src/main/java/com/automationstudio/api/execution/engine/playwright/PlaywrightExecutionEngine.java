package com.automationstudio.api.execution.engine.playwright;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionVariable;
import com.automationstudio.api.execution.engine.EngineExecutionRequest;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.EngineExecutionState;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.playwright.action.NonSecretVariableInterpolator;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionException;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionExecutionContext;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionMetricsAccumulator;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightOrderedScenarioRunner;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightScenarioExecutionOutcome;
import com.automationstudio.api.execution.engine.playwright.action.SameOriginNavigationPolicy;
import com.automationstudio.api.execution.engine.playwright.action.SelectorResolver;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightConfigurationException;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightConfigurationParser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightManifestException;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenario;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifest;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifestLoader;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntime;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeException;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeSession;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccess;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessException;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessRequest;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "automation.runner.workspace.root")
public final class PlaywrightExecutionEngine implements ExecutionEngine {

    private static final String INVALID_REQUEST = "INVALID_PLAYWRIGHT_EXECUTION_REQUEST";
    private static final String CONFIGURATION_INVALID = "PLAYWRIGHT_CONFIGURATION_INVALID";
    private static final String WORKSPACE_ACCESS_FAILED = "PLAYWRIGHT_WORKSPACE_ACCESS_FAILED";
    private static final String MANIFEST_LOAD_FAILED = "PLAYWRIGHT_MANIFEST_LOAD_FAILED";
    private static final String RUNTIME_START_FAILED = "PLAYWRIGHT_RUNTIME_START_FAILED";
    private static final String RUNTIME_EXECUTION_FAILED =
            "PLAYWRIGHT_RUNTIME_EXECUTION_FAILED";
    private static final String ACTION_EXECUTION_FAILED = "PLAYWRIGHT_ACTION_EXECUTION_FAILED";
    private static final String EXECUTION_FAILED = "PLAYWRIGHT_EXECUTION_FAILED";
    private static final String RUNTIME_CLEANUP_FAILED = "PLAYWRIGHT_RUNTIME_CLEANUP_FAILED";
    private static final String WORKSPACE_CLEANUP_FAILED =
            "PLAYWRIGHT_WORKSPACE_ACCESS_CLEANUP_FAILED";

    private final PlaywrightConfigurationParser configurationParser;
    private final EngineWorkspaceAccessResolver workspaceAccessResolver;
    private final PlaywrightScenarioManifestLoader manifestLoader;
    private final PlaywrightRuntime runtime;
    private final PlaywrightOrderedScenarioRunner scenarioRunner;
    private final SelectorResolver selectorResolver;
    private final Clock clock;

    public PlaywrightExecutionEngine(
            PlaywrightConfigurationParser configurationParser,
            EngineWorkspaceAccessResolver workspaceAccessResolver,
            PlaywrightScenarioManifestLoader manifestLoader,
            PlaywrightRuntime runtime,
            PlaywrightOrderedScenarioRunner scenarioRunner,
            SelectorResolver selectorResolver,
            Clock clock) {
        this.configurationParser = Objects.requireNonNull(
                configurationParser, "Playwright configuration parser is required");
        this.workspaceAccessResolver = Objects.requireNonNull(
                workspaceAccessResolver, "Engine workspace access resolver is required");
        this.manifestLoader = Objects.requireNonNull(
                manifestLoader, "Playwright manifest loader is required");
        this.runtime = Objects.requireNonNull(runtime, "Playwright runtime is required");
        this.scenarioRunner = Objects.requireNonNull(
                scenarioRunner, "Playwright scenario runner is required");
        this.selectorResolver = Objects.requireNonNull(
                selectorResolver, "Playwright selector resolver is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public ExecutionEngineDescriptor descriptor() {
        return PlaywrightEngineDescriptor.descriptor();
    }

    @Override
    public void validate(ExecutionContext context) {
        validateAndParse(context);
    }

    @Override
    public EngineExecutionResult execute(EngineExecutionRequest request) {
        EngineExecutionRequest validatedRequest = requireRequest(request);
        PlaywrightExecutionConfiguration configuration =
                validateAndParse(validatedRequest.context());
        validatePreparationIdentity(validatedRequest);

        OffsetDateTime startedAt = now("ENGINE_START_TIME_INVALID");
        EngineWorkspaceAccess workspaceAccess = null;
        PlaywrightRuntimeSession runtimeSession = null;
        EngineExecutionState state = null;
        RuntimeException failure = null;

        try {
            SourcePreparationResult preparation = validatedRequest.preparation();
            workspaceAccess = workspaceAccessResolver.open(
                    EngineWorkspaceAccessRequest.from(preparation));
            validateWorkspaceAccess(preparation, workspaceAccess);
            PlaywrightScenarioManifest manifest = manifestLoader.load(
                    validatedRequest.context().suite(), workspaceAccess);
            Map<String, String> variables = projectVariables(validatedRequest.context());
            NonSecretVariableInterpolator interpolator =
                    new NonSecretVariableInterpolator(variables);
            SameOriginNavigationPolicy navigationPolicy = new SameOriginNavigationPolicy(
                    validatedRequest.context().environment().baseUrl());
            PlaywrightScenario initialScenario = firstScenario(manifest);
            long totalActions = countActions(manifest);

            runtimeSession = openRuntime(configuration);
            PlaywrightActionMetricsAccumulator metrics = new PlaywrightActionMetricsAccumulator(
                    totalActions, requireStartupDuration(runtimeSession));
            PlaywrightActionExecutionContext actionContext = new PlaywrightActionExecutionContext(
                    initialScenario.id(),
                    runtimeSession,
                    configuration,
                    selectorResolver,
                    interpolator,
                    navigationPolicy,
                    validatedRequest.secretAccess()::resolve);
            PlaywrightScenarioExecutionOutcome outcome = scenarioRunner.execute(
                    manifest.scenarios(), actionContext, metrics);
            state = mapOutcome(outcome);
        } catch (RuntimeException executionFailure) {
            failure = sanitize(executionFailure);
        }

        failure = closeRuntime(runtimeSession, failure);
        failure = closeWorkspaceAccess(workspaceAccess, failure);
        if (failure != null) {
            throw failure;
        }

        OffsetDateTime finishedAt = now("ENGINE_FINISH_TIME_INVALID");
        return result(validatedRequest, state, startedAt, finishedAt);
    }

    private PlaywrightExecutionConfiguration validateAndParse(ExecutionContext context) {
        if (context == null || context.suite() == null) {
            throw invalidRequest();
        }
        if (!PlaywrightEngineDescriptor.ENGINE_ID.equals(context.suite().engineId())
                || !PlaywrightEngineDescriptor.IMPLEMENTATION_VERSION.equals(
                        context.suite().engineVersion())) {
            throw new PlaywrightExecutionException(
                    "UNSUPPORTED_PLAYWRIGHT_ENGINE",
                    "Execution request does not target the supported Playwright engine");
        }
        try {
            return configurationParser.parse(context);
        } catch (RuntimeException exception) {
            throw configurationFailure(exception);
        }
    }

    private EngineExecutionRequest requireRequest(EngineExecutionRequest request) {
        if (request == null) {
            throw invalidRequest();
        }
        return request;
    }

    private void validatePreparationIdentity(EngineExecutionRequest request) {
        SourcePreparationResult preparation = request.preparation();
        if (!request.context().executionId().equals(preparation.executionId())
                || preparation.workspace() == null
                || preparation.workspace().workspaceId() == null
                || preparation.source() == null
                || !preparation.workspace().workspaceId().equals(
                        preparation.source().workspaceId())
                || preparation.workspace().metadata() == null
                || preparation.workspace().metadata().sourceReference() == null
                || preparation.workspace().metadata().sourceReference().sourceType()
                        != preparation.source().sourceType()
                || !preparation.workspace().metadata().sourceReference().revision()
                        .equals(preparation.source().resolvedRevision())) {
            throw invalidRequest();
        }
    }

    private void validateWorkspaceAccess(
            SourcePreparationResult preparation, EngineWorkspaceAccess workspaceAccess) {
        if (workspaceAccess == null
                || !preparation.workspace().workspaceId().equals(workspaceAccess.workspaceId())) {
            throw invalidRequest();
        }
    }

    private Map<String, String> projectVariables(ExecutionContext context) {
        Map<String, String> projected = new LinkedHashMap<>();
        for (Map.Entry<String, ExecutionVariable> entry : context.variables().entrySet()) {
            ExecutionVariable variable = entry.getValue();
            if (variable == null || !entry.getKey().equals(variable.name())) {
                throw new PlaywrightExecutionException(
                        "PLAYWRIGHT_VARIABLES_INVALID",
                        "Playwright execution variables are invalid");
            }
            if (variable.value() instanceof String value) {
                projected.put(entry.getKey(), value);
            }
        }
        try {
            return Map.copyOf(projected);
        } catch (RuntimeException exception) {
            throw new PlaywrightExecutionException(
                    "PLAYWRIGHT_VARIABLES_INVALID",
                    "Playwright execution variables are invalid",
                    exception);
        }
    }

    private PlaywrightScenario firstScenario(PlaywrightScenarioManifest manifest) {
        if (manifest == null || manifest.scenarios() == null || manifest.scenarios().isEmpty()) {
            throw new PlaywrightExecutionException(
                    "PLAYWRIGHT_MANIFEST_INVARIANT_VIOLATION",
                    "Playwright scenario manifest is invalid");
        }
        return manifest.scenarios().get(0);
    }

    private long countActions(PlaywrightScenarioManifest manifest) {
        long total = 0;
        try {
            for (PlaywrightScenario scenario : manifest.scenarios()) {
                total = Math.addExact(total, scenario.steps().size());
            }
            return total;
        } catch (RuntimeException exception) {
            throw new PlaywrightExecutionException(
                    "PLAYWRIGHT_METRICS_INVALID",
                    "Playwright execution metrics are invalid",
                    exception);
        }
    }

    private Duration requireStartupDuration(PlaywrightRuntimeSession session) {
        try {
            Duration duration = session.result().metrics().browserStartupDuration();
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("Invalid browser startup duration");
            }
            return duration;
        } catch (PlaywrightRuntimeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PlaywrightExecutionException(
                    "PLAYWRIGHT_METRICS_INVALID",
                    "Playwright execution metrics are invalid",
                    exception);
        }
    }

    private EngineExecutionState mapOutcome(PlaywrightScenarioExecutionOutcome outcome) {
        if (outcome == null || outcome.status() == null || outcome.metrics() == null) {
            throw new PlaywrightExecutionException(
                    "PLAYWRIGHT_OUTCOME_INVALID",
                    "Playwright execution outcome is invalid");
        }
        return switch (outcome.status()) {
            case SUCCEEDED -> EngineExecutionState.SUCCEEDED;
            case ASSERTION_FAILED -> EngineExecutionState.FAILED;
        };
    }

    private RuntimeException closeRuntime(
            PlaywrightRuntimeSession session, RuntimeException prior) {
        if (session == null) {
            return prior;
        }
        try {
            session.close();
            return prior;
        } catch (RuntimeException cleanupFailure) {
            return cleanupFailure(
                    cleanupFailure,
                    RUNTIME_CLEANUP_FAILED,
                    "Playwright runtime cleanup failed",
                    prior);
        }
    }

    private RuntimeException closeWorkspaceAccess(
            EngineWorkspaceAccess access, RuntimeException prior) {
        if (access == null) {
            return prior;
        }
        try {
            access.close();
            return prior;
        } catch (RuntimeException cleanupFailure) {
            return cleanupFailure(
                    cleanupFailure,
                    WORKSPACE_CLEANUP_FAILED,
                    "Playwright workspace access cleanup failed",
                    prior);
        }
    }

    private RuntimeException cleanupFailure(
            RuntimeException failure,
            String code,
            String message,
            RuntimeException prior) {
        RuntimeException sanitized = new PlaywrightExecutionException(code, message, failure);
        if (prior != null && prior != sanitized) {
            sanitized.addSuppressed(prior);
        }
        return sanitized;
    }

    private RuntimeException sanitize(RuntimeException failure) {
        if (failure instanceof PlaywrightExecutionException) {
            return failure;
        }
        if (failure instanceof PlaywrightConfigurationException) {
            return configurationFailure(failure);
        }
        if (failure instanceof EngineWorkspaceAccessException) {
            return new PlaywrightExecutionException(
                    WORKSPACE_ACCESS_FAILED,
                    "Playwright execution workspace is unavailable",
                    failure);
        }
        if (failure instanceof PlaywrightManifestException) {
            return new PlaywrightExecutionException(
                    MANIFEST_LOAD_FAILED,
                    "Playwright scenario manifest could not be loaded",
                    failure);
        }
        if (failure instanceof PlaywrightRuntimeException) {
            return new PlaywrightExecutionException(
                    RUNTIME_EXECUTION_FAILED,
                    "Playwright runtime execution failed",
                    failure);
        }
        if (failure instanceof PlaywrightActionException) {
            return new PlaywrightExecutionException(
                    ACTION_EXECUTION_FAILED,
                    "Playwright action execution failed",
                    failure);
        }
        return new PlaywrightExecutionException(
                EXECUTION_FAILED,
                "Playwright execution failed",
                failure);
    }

    private PlaywrightRuntimeSession openRuntime(
            PlaywrightExecutionConfiguration configuration) {
        try {
            PlaywrightRuntimeSession session = runtime.open(configuration);
            if (session == null) {
                throw new PlaywrightExecutionException(
                        RUNTIME_START_FAILED,
                        "Playwright runtime could not be started",
                        new IllegalStateException("Playwright runtime returned no session"));
            }
            return session;
        } catch (PlaywrightRuntimeException failure) {
            throw new PlaywrightExecutionException(
                    RUNTIME_START_FAILED,
                    "Playwright runtime could not be started",
                    failure);
        }
    }

    private PlaywrightExecutionException configurationFailure(RuntimeException failure) {
        return new PlaywrightExecutionException(
                CONFIGURATION_INVALID,
                "Playwright execution configuration is invalid",
                failure);
    }

    private EngineExecutionResult result(
            EngineExecutionRequest request,
            EngineExecutionState state,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
        try {
            return new EngineExecutionResult(
                    request.executionId(),
                    descriptor().engineId(),
                    descriptor().implementationVersion(),
                    request.preparation().workspace().workspaceId(),
                    request.preparation().source().resolvedRevision(),
                    state,
                    startedAt,
                    finishedAt,
                    Duration.between(startedAt, finishedAt));
        } catch (RuntimeException exception) {
            throw new PlaywrightExecutionException(
                    "PLAYWRIGHT_RESULT_INVALID",
                    "Playwright execution result is invalid",
                    exception);
        }
    }

    private OffsetDateTime now(String code) {
        try {
            return OffsetDateTime.now(clock);
        } catch (RuntimeException exception) {
            throw new PlaywrightExecutionException(
                    code, "Playwright execution timing is invalid", exception);
        }
    }

    private PlaywrightExecutionException invalidRequest() {
        return new PlaywrightExecutionException(
                INVALID_REQUEST, "Playwright execution request is invalid");
    }
}
