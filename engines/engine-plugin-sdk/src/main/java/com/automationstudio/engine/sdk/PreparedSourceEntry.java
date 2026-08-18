package com.automationstudio.engine.sdk;

import java.util.List;
import java.util.Objects;

/** Safe logical metadata for one direct child of a prepared-source directory. */
public record PreparedSourceEntry(
        String repositoryRelativePath,
        PreparedSourceEntryKind kind,
        long sizeBytes) implements Comparable<PreparedSourceEntry> {

    public static final long UNKNOWN_SIZE = -1;
    public static final int MAX_LOGICAL_PATH_LENGTH = 4_096;

    public PreparedSourceEntry {
        repositoryRelativePath = validPath(repositoryRelativePath);
        kind = Objects.requireNonNull(kind, "Prepared source entry kind must not be null");
        if (sizeBytes < UNKNOWN_SIZE || (kind != PreparedSourceEntryKind.FILE
                && sizeBytes != UNKNOWN_SIZE)) {
            throw new IllegalArgumentException("Prepared source entry size is invalid");
        }
    }

    @Override
    public int compareTo(PreparedSourceEntry other) {
        return repositoryRelativePath.compareTo(
                Objects.requireNonNull(other, "Prepared source entry must not be null")
                        .repositoryRelativePath);
    }

    private static String validPath(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_LOGICAL_PATH_LENGTH
                || path.startsWith("/") || path.startsWith("\\") || path.contains("\\")
                || path.contains(":") || path.indexOf('\0') >= 0
                || List.of(path.split("/", -1)).stream()
                        .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("Prepared source entry path is invalid");
        }
        return path;
    }
}
