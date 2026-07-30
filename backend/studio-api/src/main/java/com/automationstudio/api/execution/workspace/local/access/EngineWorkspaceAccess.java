package com.automationstudio.api.execution.workspace.local.access;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import java.nio.file.Path;

public interface EngineWorkspaceAccess extends AutoCloseable {

    WorkspaceId workspaceId();

    Path sourceDirectory();

    Path artifactsDirectory();

    Path metadataDirectory();

    Path temporaryDirectory();

    boolean isOpen();

    @Override
    void close();
}
