package com.automationstudio.engine.conformance;

import com.automationstudio.engine.sdk.PreparedSourceAccess;
import com.automationstudio.engine.sdk.PreparedSourceEntry;
import com.automationstudio.engine.sdk.PreparedSourceEntryKind;
import com.automationstudio.engine.sdk.WorkspaceAccess;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** SDK-only prepared-source fixture with observable handle lifecycle. */
public final class InMemoryWorkspaceAccess implements WorkspaceAccess {
    private final UUID executionId;
    private final WorkspaceId workspaceId;
    private final Map<String, byte[]> files;
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    public InMemoryWorkspaceAccess(UUID executionId, WorkspaceId workspaceId,
            Map<String, byte[]> files) {
        this.executionId = Objects.requireNonNull(executionId);
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.files = new LinkedHashMap<>();
        Objects.requireNonNull(files).forEach((path, value) ->
                this.files.put(validPath(path), Arrays.copyOf(value, value.length)));
    }

    @Override public UUID executionId() { return executionId; }
    @Override public WorkspaceId workspaceId() { return workspaceId; }
    public int openedHandleCount() { return opened.get(); }
    public int closedHandleCount() { return closed.get(); }

    @Override
    public PreparedSourceAccess openPreparedSource() {
        opened.incrementAndGet();
        return new PreparedSourceAccess() {
            private boolean open = true;
            @Override public WorkspaceId workspaceId() { return workspaceId; }
            @Override public boolean isOpen() { return open; }
            @Override public InputStream open(String path) {
                if (!open) throw new IllegalStateException("Prepared source access is closed");
                byte[] value = files.get(validPath(path));
                if (value == null) throw new IllegalArgumentException("Prepared source path not found");
                return new ByteArrayInputStream(Arrays.copyOf(value, value.length));
            }
            @Override public List<PreparedSourceEntry> list(String directory, int maxEntries) {
                if (!open) throw new IllegalStateException("Prepared source access is closed");
                if (maxEntries < 1 || maxEntries > 10_000) {
                    throw new IllegalArgumentException("Prepared source entry limit is invalid");
                }
                String prefix = validDirectory(directory);
                Map<String, PreparedSourceEntry> entries = new TreeMap<>();
                files.forEach((path, bytes) -> {
                    if (!path.startsWith(prefix)) return;
                    String remainder = path.substring(prefix.length());
                    int separator = remainder.indexOf('/');
                    String child = separator < 0 ? remainder : remainder.substring(0, separator);
                    String logical = prefix + child;
                    entries.putIfAbsent(logical, new PreparedSourceEntry(logical,
                            separator < 0 ? PreparedSourceEntryKind.FILE
                                    : PreparedSourceEntryKind.DIRECTORY,
                            separator < 0 ? bytes.length : PreparedSourceEntry.UNKNOWN_SIZE));
                });
                if (entries.size() > maxEntries) {
                    throw new IllegalArgumentException("Prepared source entry limit exceeded");
                }
                return List.copyOf(entries.values());
            }
            @Override public void close() {
                if (open) { open = false; closed.incrementAndGet(); }
            }
        };
    }

    private static String validPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("..") || path.contains(":")) {
            throw new IllegalArgumentException("Path must be repository-relative");
        }
        return path.replace('\\', '/');
    }

    private static String validDirectory(String directory) {
        if (directory == null) throw new IllegalArgumentException("Directory must not be null");
        if (directory.isEmpty()) return "";
        return validPath(directory) + "/";
    }
}
