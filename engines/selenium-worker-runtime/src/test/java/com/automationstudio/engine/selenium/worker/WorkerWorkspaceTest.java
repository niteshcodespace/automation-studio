package com.automationstudio.engine.selenium.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerWorkspaceTest {
    @TempDir java.nio.file.Path temporary;
    @Test void derivesFixedSafePathsAndCleansIdempotently() throws Exception {
        UUID id = UUID.randomUUID(); WorkerWorkspace workspace = WorkerWorkspace.create(temporary, id);
        assertEquals(temporary.toAbsolutePath().normalize(), workspace.root().getParent());
        assertTrue(Files.isDirectory(workspace.root().resolve("profile")));
        assertTrue(Files.isDirectory(workspace.root().resolve("ipc")));
        workspace.close(); workspace.close(); assertFalse(Files.exists(workspace.root()));
    }
    @Test void acceptsNoCallerPathComponent() {
        assertThrows(IllegalArgumentException.class, () -> WorkerWorkspace.create(temporary, null));
    }
}
