package com.automationstudio.engine.selenium.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class WorkerWorkspace implements AutoCloseable {
    private final Path root;
    private final AtomicBoolean closed = new AtomicBoolean();
    private WorkerWorkspace(Path root) { this.root = root; }
    static WorkerWorkspace create(Path fixedBase, UUID executionId) throws IOException {
        if (fixedBase == null || executionId == null) throw new IllegalArgumentException("Invalid workspace identity");
        Path normalizedBase = fixedBase.toAbsolutePath().normalize();
        Path root = normalizedBase.resolve(executionId.toString().replace("-", "")).normalize();
        if (!root.getParent().equals(normalizedBase)) throw new IllegalArgumentException("Invalid workspace identity");
        Files.createDirectories(root.resolve("profile"));
        Files.createDirectories(root.resolve("ipc"));
        return new WorkerWorkspace(root);
    }
    Path root() { return root; }
    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true) || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
