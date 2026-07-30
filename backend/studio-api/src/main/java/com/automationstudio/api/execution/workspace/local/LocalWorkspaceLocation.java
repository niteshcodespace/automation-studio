package com.automationstudio.api.execution.workspace.local;

import java.nio.file.Path;
import java.util.Objects;

public record LocalWorkspaceLocation(
        Path workspaceDirectory,
        Path sourceDirectory,
        Path metadataDirectory,
        Path artifactsDirectory,
        Path tempDirectory) {

    public LocalWorkspaceLocation {
        workspaceDirectory = Objects.requireNonNull(
                workspaceDirectory, "Workspace directory must not be null");
        sourceDirectory = Objects.requireNonNull(
                sourceDirectory, "Source directory must not be null");
        metadataDirectory = Objects.requireNonNull(
                metadataDirectory, "Metadata directory must not be null");
        artifactsDirectory = Objects.requireNonNull(
                artifactsDirectory, "Artifacts directory must not be null");
        tempDirectory = Objects.requireNonNull(
                tempDirectory, "Temporary directory must not be null");
    }
}
