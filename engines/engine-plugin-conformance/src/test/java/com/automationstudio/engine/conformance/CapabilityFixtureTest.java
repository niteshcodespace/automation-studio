package com.automationstudio.engine.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.automationstudio.engine.sdk.SecretResolutionException;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityFixtureTest {
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
        assertEquals(1, workspace.openedHandleCount());
        assertEquals(1, workspace.closedHandleCount());
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
