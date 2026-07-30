package com.automationstudio.api.execution.workspace;

import com.automationstudio.api.source.ExecutionSourceReference;
import java.util.Objects;

public final class WorkspaceManager {

    private final WorkspaceProvider provider;

    public WorkspaceManager(WorkspaceProvider provider) {
        this.provider = Objects.requireNonNull(provider, "Workspace provider must not be null");
    }

    public WorkspaceDescriptor prepare(
            WorkspaceDescriptor planned,
            ExecutionSourceReference sourceReference) {
        requireProvider(planned);
        if (planned.state() != WorkspaceState.PLANNED) {
            throw new WorkspaceContractException(
                    "Workspace manager preparation requires PLANNED state");
        }
        WorkspaceDescriptor preparing =
                planned.transitionTo(WorkspaceState.PREPARING, null);
        try {
            return provider.prepare(
                    new WorkspacePreparationRequest(preparing, sourceReference))
                    .workspace();
        } catch (WorkspaceContractException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WorkspaceManagementException(
                    "Workspace preparation failed", exception);
        }
    }

    public WorkspaceDescriptor release(WorkspaceDescriptor workspace) {
        requireProvider(workspace);
        if (workspace.state() == WorkspaceState.RELEASED) {
            return workspace;
        }
        if (workspace.state() != WorkspaceState.READY
                && workspace.state() != WorkspaceState.IN_USE) {
            throw new WorkspaceContractException(
                    "Workspace manager release requires READY, IN_USE, or RELEASED state");
        }
        WorkspaceDescriptor releasable = workspace.state() == WorkspaceState.READY
                ? workspace.transitionTo(WorkspaceState.IN_USE, null)
                : workspace;
        WorkspaceDescriptor releasing =
                releasable.transitionTo(WorkspaceState.RELEASING, null);
        try {
            return provider.release(new WorkspaceReleaseRequest(releasing))
                    .workspace();
        } catch (WorkspaceContractException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WorkspaceManagementException(
                    "Workspace release failed", exception);
        }
    }

    private void requireProvider(WorkspaceDescriptor workspace) {
        Objects.requireNonNull(workspace, "Workspace must not be null");
        if (!provider.providerId().equals(workspace.providerId())) {
            throw new WorkspaceContractException(
                    "Workspace provider does not own the workspace");
        }
    }
}
