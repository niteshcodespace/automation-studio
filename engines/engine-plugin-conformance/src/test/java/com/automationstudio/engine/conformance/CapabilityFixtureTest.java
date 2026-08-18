package com.automationstudio.engine.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.automationstudio.engine.sdk.SecretResolutionException;
import com.automationstudio.engine.sdk.PreparedSourceEntryKind;
import com.automationstudio.engine.sdk.PreparedSourceEntry;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityFixtureTest {
    @Test
    void preparedSourceEntryValidatesProviderNeutralLogicalMetadata() {
        var entry = new PreparedSourceEntry(
                "features/login.feature", PreparedSourceEntryKind.FILE, 42);

        assertEquals("features/login.feature", entry.repositoryRelativePath());
        assertEquals(PreparedSourceEntryKind.FILE, entry.kind());
        assertEquals(42, entry.sizeBytes());
        assertThrows(IllegalArgumentException.class, () -> new PreparedSourceEntry(
                "../outside", PreparedSourceEntryKind.FILE, 1));
        assertThrows(IllegalArgumentException.class, () -> new PreparedSourceEntry(
                "C:/outside", PreparedSourceEntryKind.FILE, 1));
        assertThrows(IllegalArgumentException.class, () -> new PreparedSourceEntry(
                "features", PreparedSourceEntryKind.DIRECTORY, 0));
    }

    @Test
    void preparedSourceIsBoundedAndLifecycleObservable() throws Exception {
        UUID executionId = UUID.randomUUID();
        var workspace = new InMemoryWorkspaceAccess(executionId,
                new WorkspaceId(UUID.randomUUID()), Map.of("a.txt", new byte[] {1, 2}));
        var access = workspace.openPreparedSource();
        assertArrayEquals(new byte[] {1, 2}, access.open("a.txt").readAllBytes());
        assertThrows(IllegalArgumentException.class, () -> access.open("../outside"));
        access.close();
        assertFalse(access.isOpen());
        assertThrows(IllegalStateException.class, () -> access.open("a.txt"));
        assertThrows(IllegalStateException.class, () -> access.list("", 1));
        assertEquals(1, workspace.openedHandleCount());
        assertEquals(1, workspace.closedHandleCount());
    }

    @Test
    void preparedSourceListingIsBoundedDeterministicImmutableAndNonRecursive() {
        UUID executionId = UUID.randomUUID();
        var workspace = new InMemoryWorkspaceAccess(executionId,
                new WorkspaceId(UUID.randomUUID()), Map.of(
                        "z.feature", new byte[] {1},
                        "features/b.feature", new byte[] {1, 2},
                        "features/a.feature", new byte[] {1, 2, 3},
                        "features/nested/c.feature", new byte[] {4}));
        try (var access = workspace.openPreparedSource()) {
            var root = access.list("", 3);
            assertEquals(List.of("features", "z.feature"), root.stream()
                    .map(entry -> entry.repositoryRelativePath()).toList());
            assertEquals(PreparedSourceEntryKind.DIRECTORY, root.getFirst().kind());
            var nested = access.list("features", 3);
            assertEquals(List.of("features/a.feature", "features/b.feature", "features/nested"),
                    nested.stream().map(entry -> entry.repositoryRelativePath()).toList());
            assertEquals(3, nested.getFirst().sizeBytes());
            assertThrows(UnsupportedOperationException.class, () -> nested.add(root.getFirst()));
            assertThrows(IllegalArgumentException.class, () -> access.list("../outside", 3));
            assertThrows(IllegalArgumentException.class, () -> access.list("/outside", 3));
            assertThrows(IllegalArgumentException.class, () -> access.list("features", 2));
        }
    }

    @Test
    void secretsAreExecutionScopedCopiedClosedAndRedacted() {
        UUID executionId = UUID.randomUUID();
        char[] original = "token-value".toCharArray();
        var access = new InMemoryExecutionSecretAccess(executionId, Map.of("token", original));
        original[0] = 'X';
        var secret = access.resolve("token");
        secret.withValue(value -> assertArrayEquals("token-value".toCharArray(), value));
        assertFalse(secret.toString().contains("token-value"));
        assertFalse(access.toString().contains("token-value"));
        secret.close();
        assertTrue(secret.isClosed());
        assertThrows(SecretResolutionException.class, () -> secret.withValue(value -> {}));
        assertThrows(SecretResolutionException.class, () -> access.resolve("missing"));
        assertEquals(executionId, access.executionId());
        assertEquals(1, access.resolutionCount());
    }
}
