package com.automationstudio.api.execution.business;

import com.automationstudio.api.execution.engine.playwright.PlaywrightExecutionException;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionException;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightManifestException;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntimeException;
import com.automationstudio.api.execution.orchestration.ExecutionOrchestrationException;
import com.automationstudio.api.execution.orchestration.ExecutionOrchestrationRequest;
import com.automationstudio.api.execution.orchestration.ExecutionOrchestrationResult;
import com.automationstudio.api.execution.orchestration.ExecutionOrchestrator;
import com.automationstudio.api.execution.preparation.SourcePreparationException;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class OrangeHrmQualificationDiagnosticProbe implements ExecutionOrchestrator {

    private static final Set<String> APPROVED_CODES = Set.of(
            "WORKSPACE_PREPARATION_FAILED", "SOURCE_MATERIALIZATION_FAILED",
            "PREPARATION_INVARIANT_VIOLATION", "WORKSPACE_COMPENSATION_FAILED",
            "WORKSPACE_NOT_FOUND", "WORKSPACE_ACCESS_DENIED",
            "WORKSPACE_PATH_ESCAPE_DETECTED", "WORKSPACE_LAYOUT_INVALID",
            "MANIFEST_UNREADABLE", "UNSAFE_MANIFEST_LOCATION", "MANIFEST_MISSING",
            "INVALID_MANIFEST", "MANIFEST_TOO_LARGE", "UNSUPPORTED_SCHEMA_VERSION",
            "MALFORMED_JSON", "INVALID_SCENARIO", "INVALID_STEP",
            "PLAYWRIGHT_UNAVAILABLE", "BROWSER_LAUNCH_FAILED", "CONTEXT_CREATION_FAILED",
            "PAGE_CREATION_FAILED", "PLAYWRIGHT_WORKSPACE_ACCESS_FAILED",
            "PLAYWRIGHT_MANIFEST_LOAD_FAILED", "PLAYWRIGHT_RUNTIME_START_FAILED",
            "PLAYWRIGHT_RUNTIME_EXECUTION_FAILED", "PLAYWRIGHT_ACTION_EXECUTION_FAILED",
            "INVALID_PLAYWRIGHT_EXECUTION_REQUEST",
            "PLAYWRIGHT_EXECUTION_FAILED", "PLAYWRIGHT_RUNTIME_CLEANUP_FAILED",
            "PLAYWRIGHT_WORKSPACE_ACCESS_CLEANUP_FAILED", "SOURCE_PREPARATION_FAILED",
            "ENGINE_NOT_FOUND", "ENGINE_EXECUTION_FAILED", "SECRET_SCOPE_CREATION_FAILED",
            "SECRET_SCOPE_CLEANUP_FAILED", "WORKSPACE_CLEANUP_FAILED");

    private final ExecutionOrchestrator delegate;
    private final AtomicReference<Diagnostic> diagnostic = new AtomicReference<>();

    public OrangeHrmQualificationDiagnosticProbe(ExecutionOrchestrator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "AS-025G orchestrator must not be null");
    }

    @Override
    public ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request) {
        diagnostic.set(null);
        try {
            return delegate.execute(request);
        } catch (RuntimeException failure) {
            diagnostic.set(classify(failure));
            throw failure;
        }
    }

    public Optional<Diagnostic> diagnostic() {
        return Optional.ofNullable(diagnostic.get());
    }

    private static Diagnostic classify(RuntimeException failure) {
        List<Throwable> chain = new ArrayList<>();
        Throwable current = failure;
        while (current != null && chain.size() < 16) {
            chain.add(current);
            current = current.getCause();
        }
        for (int index = chain.size() - 1; index >= 0; index--) {
            Diagnostic classified = classifyKnown(chain.get(index));
            if (classified != null) {
                return classified;
            }
        }
        return new Diagnostic(
                Stage.UNKNOWN_POST_START, failure.getClass().getSimpleName(), null);
    }

    private static Diagnostic classifyKnown(Throwable failure) {
        if (failure instanceof PlaywrightManifestException exception) {
            return diagnostic(Stage.MANIFEST_LOADING, exception);
        }
        if (failure instanceof EngineWorkspaceAccessException exception) {
            return diagnostic(Stage.WORKSPACE_ACCESS, exception);
        }
        if (failure instanceof PlaywrightActionException exception) {
            return diagnostic(Stage.ACTION_EXECUTION, exception);
        }
        if (failure instanceof PlaywrightRuntimeException exception) {
            return diagnostic(Stage.PLAYWRIGHT_RUNTIME_OPEN, exception);
        }
        if (failure instanceof SourcePreparationException exception) {
            Stage stage = "SOURCE_MATERIALIZATION_FAILED".equals(exception.code())
                    ? Stage.SOURCE_MATERIALIZATION : Stage.SOURCE_PREPARATION;
            return new Diagnostic(
                    stage, exception.getClass().getSimpleName(), approvedCode(exception.code()));
        }
        if (failure instanceof PlaywrightExecutionException exception) {
            Stage stage = switch (exception.code()) {
                case "INVALID_PLAYWRIGHT_EXECUTION_REQUEST" ->
                    Stage.ENGINE_REQUEST_VALIDATION;
                case "PLAYWRIGHT_WORKSPACE_ACCESS_FAILED" -> Stage.WORKSPACE_ACCESS;
                case "PLAYWRIGHT_MANIFEST_LOAD_FAILED" -> Stage.MANIFEST_LOADING;
                case "PLAYWRIGHT_RUNTIME_START_FAILED" -> Stage.PLAYWRIGHT_RUNTIME_OPEN;
                case "PLAYWRIGHT_ACTION_EXECUTION_FAILED",
                        "PLAYWRIGHT_RUNTIME_EXECUTION_FAILED" -> Stage.ACTION_EXECUTION;
                case "PLAYWRIGHT_RUNTIME_CLEANUP_FAILED",
                        "PLAYWRIGHT_WORKSPACE_ACCESS_CLEANUP_FAILED" ->
                    Stage.ORCHESTRATION_CLEANUP;
                default -> Stage.UNKNOWN_POST_START;
            };
            return new Diagnostic(
                    stage, exception.getClass().getSimpleName(), approvedCode(exception.code()));
        }
        if (failure instanceof ExecutionOrchestrationException exception) {
            Stage stage = switch (exception.code()) {
                case "SOURCE_PREPARATION_FAILED" -> Stage.SOURCE_PREPARATION;
                case "ENGINE_NOT_FOUND" -> Stage.ENGINE_RESOLUTION;
                case "SECRET_SCOPE_CREATION_FAILED" -> Stage.SECRET_RESOLUTION;
                case "SECRET_SCOPE_CLEANUP_FAILED", "WORKSPACE_CLEANUP_FAILED" ->
                    Stage.ORCHESTRATION_CLEANUP;
                default -> Stage.UNKNOWN_POST_START;
            };
            return new Diagnostic(
                    stage, exception.getClass().getSimpleName(), approvedCode(exception.code()));
        }
        return null;
    }

    private static Diagnostic diagnostic(Stage stage, PlaywrightManifestException exception) {
        return new Diagnostic(
                stage, exception.getClass().getSimpleName(), approvedCode(exception.code()));
    }

    private static Diagnostic diagnostic(Stage stage, EngineWorkspaceAccessException exception) {
        return new Diagnostic(
                stage, exception.getClass().getSimpleName(), approvedCode(exception.code()));
    }

    private static Diagnostic diagnostic(Stage stage, PlaywrightActionException exception) {
        return new Diagnostic(
                stage, exception.getClass().getSimpleName(), approvedCode(exception.code()));
    }

    private static Diagnostic diagnostic(Stage stage, PlaywrightRuntimeException exception) {
        return new Diagnostic(
                stage, exception.getClass().getSimpleName(), approvedCode(exception.code()));
    }

    private static String approvedCode(String code) {
        return APPROVED_CODES.contains(code) ? code : null;
    }

    public enum Stage {
        SOURCE_PREPARATION,
        WORKSPACE_ACCESS,
        SOURCE_MATERIALIZATION,
        ENGINE_RESOLUTION,
        ENGINE_REQUEST_VALIDATION,
        MANIFEST_LOADING,
        PLAYWRIGHT_RUNTIME_OPEN,
        SECRET_RESOLUTION,
        ACTION_EXECUTION,
        ORCHESTRATION_CLEANUP,
        UNKNOWN_POST_START
    }

    public record Diagnostic(Stage stage, String exceptionClass, String errorCode) {
        public Diagnostic {
            Objects.requireNonNull(stage, "AS-025G diagnostic stage must not be null");
            Objects.requireNonNull(
                    exceptionClass, "AS-025G diagnostic exception class must not be null");
        }
    }
}
