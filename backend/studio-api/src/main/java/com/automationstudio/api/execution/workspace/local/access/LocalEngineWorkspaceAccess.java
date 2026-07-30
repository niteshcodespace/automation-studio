package com.automationstudio.api.execution.workspace.local.access;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.local.LocalWorkspaceLocation;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class LocalEngineWorkspaceAccess implements EngineWorkspaceAccess {

    private final WorkspaceId workspaceId;
    private final LocalWorkspaceLocation location;
    private final AtomicBoolean open = new AtomicBoolean(true);

    LocalEngineWorkspaceAccess(
            WorkspaceId workspaceId,
            LocalWorkspaceLocation location) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "Workspace ID must not be null");
        this.location = Objects.requireNonNull(location, "Workspace location must not be null");
    }

    @Override
    public WorkspaceId workspaceId() {
        requireOpen();
        return workspaceId;
    }

    @Override
    public Path sourceDirectory() {
        requireOpen();
        return location.sourceDirectory();
    }

    @Override
    public Path artifactsDirectory() {
        requireOpen();
        return location.artifactsDirectory();
    }

    @Override
    public Path metadataDirectory() {
        requireOpen();
        return location.metadataDirectory();
    }

    @Override
    public Path temporaryDirectory() {
        requireOpen();
        return location.tempDirectory();
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        open.set(false);
    }

    private void requireOpen() {
        if (!open.get()) {
            throw new EngineWorkspaceAccessException(
                    "WORKSPACE_ACCESS_CLOSED",
                    "Engine workspace access is closed");
        }
    }
}
