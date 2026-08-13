package com.automationstudio.api.execution.workspace.local.access;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.engine.sdk.PreparedSourceEntry;
import com.automationstudio.engine.sdk.PreparedSourceEntryKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

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

    @Override
    default List<PreparedSourceEntry> list(
            String repositoryRelativeDirectory, int maxEntries) {
        if (!isOpen()) {
            throw new EngineWorkspaceAccessException(
                    "WORKSPACE_ACCESS_CLOSED", "Prepared source access is closed");
        }
        if (maxEntries < 1 || maxEntries > 10_000) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_ENTRY_LIMIT", "Prepared source entry limit is invalid");
        }
        Path relative = relativeDirectory(repositoryRelativeDirectory);
        try {
            Path root = sourceDirectory().toRealPath();
            Path directory = root.resolve(relative).normalize();
            if (!directory.startsWith(root)
                    || Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !directory.toRealPath().startsWith(root)) {
                throw new EngineWorkspaceAccessException(
                        "PATH_OUTSIDE_PREPARED_SOURCE",
                        "Prepared source directory is outside its boundary");
            }
            List<PreparedSourceEntry> entries = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    if (entries.size() == maxEntries) {
                        throw new EngineWorkspaceAccessException(
                                "ENTRY_LIMIT_EXCEEDED",
                                "Prepared source entry limit was exceeded");
                    }
                    entries.add(entry(root, child));
                }
            }
            entries.sort(PreparedSourceEntry::compareTo);
            return List.copyOf(entries);
        } catch (EngineWorkspaceAccessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new EngineWorkspaceAccessException(
                    "PREPARED_SOURCE_UNREADABLE",
                    "Prepared source directory could not be listed", exception);
        }
    }

    private static Path relativeDirectory(String value) {
        if (value == null || value.length() > PreparedSourceEntry.MAX_LOGICAL_PATH_LENGTH
                || value.startsWith("/") || value.startsWith("\\") || value.contains("\\")
                || value.contains(":") || value.indexOf('\0') >= 0) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_RELATIVE_PATH", "Prepared source directory is invalid");
        }
        if (value.isEmpty()) return Path.of("");
        List<String> parts = List.of(value.split("/", -1));
        if (parts.stream().anyMatch(
                part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_RELATIVE_PATH", "Prepared source directory is invalid");
        }
        try {
            Path relative = Path.of(value);
            if (relative.isAbsolute()) throw new IllegalArgumentException();
            return relative;
        } catch (RuntimeException exception) {
            throw new EngineWorkspaceAccessException(
                    "INVALID_RELATIVE_PATH", "Prepared source directory is invalid");
        }
    }

    private static PreparedSourceEntry entry(Path root, Path child) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        PreparedSourceEntryKind kind;
        long size = PreparedSourceEntry.UNKNOWN_SIZE;
        if (attributes.isSymbolicLink()) {
            kind = PreparedSourceEntryKind.LINK;
        } else if (attributes.isRegularFile()) {
            if (!child.toRealPath().startsWith(root)) {
                throw new EngineWorkspaceAccessException(
                        "PATH_OUTSIDE_PREPARED_SOURCE",
                        "Prepared source entry is outside its boundary");
            }
            kind = PreparedSourceEntryKind.FILE;
            size = attributes.size();
        } else if (attributes.isDirectory()) {
            if (!child.toRealPath().startsWith(root)) {
                throw new EngineWorkspaceAccessException(
                        "PATH_OUTSIDE_PREPARED_SOURCE",
                        "Prepared source entry is outside its boundary");
            }
            kind = PreparedSourceEntryKind.DIRECTORY;
        } else {
            kind = PreparedSourceEntryKind.UNSUPPORTED;
        }
        String logical = root.relativize(child.normalize()).toString().replace('\\', '/');
        return new PreparedSourceEntry(logical, kind, size);
    }

    Path artifactsDirectory();

    Path metadataDirectory();

    Path temporaryDirectory();

    boolean isOpen();

    @Override
    void close();
}
