package com.automationstudio.api.execution.workspace.local.access;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public interface EngineWorkspaceAccess
        extends com.automationstudio.engine.sdk.PreparedSourceAccess {

    WorkspaceId workspaceId();

    Path sourceDirectory();

    @Override
    default InputStream open(String repositoryRelativePath) {
        if (!isOpen()) {
            throw new EngineWorkspaceAccessException(
                    "WORKSPACE_ACCESS_CLOSED", "Prepared source access is closed");
        }
        if (repositoryRelativePath == null || repositoryRelativePath.isBlank()) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_RELATIVE_PATH", "Prepared source path is invalid");
        }
        Path relative;
        try {
            relative = Path.of(repositoryRelativePath);
        } catch (RuntimeException exception) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_RELATIVE_PATH", "Prepared source path is invalid", exception);
        }
        if (relative.isAbsolute()) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_RELATIVE_PATH", "Prepared source path is invalid");
        }
        try {
            Path root = sourceDirectory().toRealPath();
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root)
                    || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(resolved)
                    || !resolved.toRealPath().startsWith(root)) {
                throw new EngineWorkspaceAccessException(
                        "PATH_OUTSIDE_PREPARED_SOURCE", "Prepared source path is outside its boundary");
            }
            return Files.newInputStream(resolved);
        } catch (EngineWorkspaceAccessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new EngineWorkspaceAccessException(
                    "PREPARED_SOURCE_UNREADABLE", "Prepared source could not be opened", exception);
        }
    }

    Path artifactsDirectory();

    Path metadataDirectory();

    Path temporaryDirectory();

    boolean isOpen();

    @Override
    void close();
}
