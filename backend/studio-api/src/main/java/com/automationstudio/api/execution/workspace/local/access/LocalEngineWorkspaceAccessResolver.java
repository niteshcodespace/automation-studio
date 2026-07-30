package com.automationstudio.api.execution.workspace.local.access;

import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceException;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceLocation;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceProvider;
import java.nio.file.AccessDeniedException;
import java.util.Objects;

public final class LocalEngineWorkspaceAccessResolver
        implements EngineWorkspaceAccessResolver {

    private final LocalWorkspaceProvider workspaceProvider;

    public LocalEngineWorkspaceAccessResolver(LocalWorkspaceProvider workspaceProvider) {
        this.workspaceProvider = Objects.requireNonNull(
                workspaceProvider, "Local workspace provider must not be null");
    }

    @Override
    public EngineWorkspaceAccess open(EngineWorkspaceAccessRequest request) {
        if (request == null) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_ACCESS_REQUEST",
                    "Engine workspace access request must not be null");
        }
        SourcePreparationResult preparation = request.trustedPreparation();
        WorkspaceDescriptor workspace = preparation.workspace();
        if (!LocalWorkspaceProvider.PROVIDER_ID.equals(workspace.providerId())
                || !request.executionId().equals(workspace.executionId())
                || !workspace.workspaceId().equals(preparation.source().workspaceId())
                || !workspace.metadata().sourceReference().revision()
                        .equals(preparation.source().resolvedRevision())) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_ACCESS_REQUEST",
                    "Engine workspace access request is inconsistent");
        }
        try {
            LocalWorkspaceLocation location =
                    workspaceProvider.resolve(workspace.workspaceId());
            return new LocalEngineWorkspaceAccess(workspace.workspaceId(), location);
        } catch (LocalWorkspaceException failure) {
            throw translate(failure);
        } catch (RuntimeException failure) {
            throw new EngineWorkspaceAccessException(
                    "WORKSPACE_ACCESS_DENIED",
                    "Engine workspace access could not be validated",
                    failure);
        }
    }

    private EngineWorkspaceAccessException translate(LocalWorkspaceException failure) {
        String message = failure.getMessage();
        String code;
        String safeMessage;
        if ("Workspace does not exist".equals(message)) {
            code = "WORKSPACE_NOT_FOUND";
            safeMessage = "Engine workspace was not found";
        } else if (containsAccessDenied(failure)) {
            code = "WORKSPACE_ACCESS_DENIED";
            safeMessage = "Engine workspace access was denied";
        } else if (message != null
                && (message.contains("link")
                        || message.contains("canonical")
                        || message.contains("outside")
                        || message.contains("escapes")
                        || message.contains("unsupported"))) {
            code = "WORKSPACE_PATH_ESCAPE_DETECTED";
            safeMessage = "Engine workspace path validation failed";
        } else {
            code = "WORKSPACE_LAYOUT_INVALID";
            safeMessage = "Engine workspace layout is invalid";
        }
        return new EngineWorkspaceAccessException(code, safeMessage, failure);
    }

    private boolean containsAccessDenied(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AccessDeniedException
                    || current instanceof SecurityException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
