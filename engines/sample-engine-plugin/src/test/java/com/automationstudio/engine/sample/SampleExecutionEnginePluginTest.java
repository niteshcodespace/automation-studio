package com.automationstudio.engine.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.automationstudio.engine.conformance.InMemoryExecutionSecretAccess;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SampleExecutionEnginePluginTest {

    private final SampleExecutionEnginePlugin plugin = new SampleExecutionEnginePlugin(Clock.fixed(
            Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void exposesExactReferenceIdentity() {
        assertEquals("sample-engine", plugin.descriptor().engineId());
        assertEquals("1.0.0", plugin.descriptor().implementationVersion());
    }

    @Test
    void rejectsInvalidConfigurationWithoutDisclosingItsValue() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> plugin.validate(context(UUID.randomUUID(), Map.of("mode", "private-value"))));
        assertEquals("Sample engine configuration is invalid", failure.getMessage());
    }

    @Test
    void returnsOnlySanitizedFailureWhenPreparedInputIsInvalid() {
        UUID executionId = UUID.randomUUID();
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        var request = new EngineExecutionRequest(
                context(executionId, Map.of("mode", "DETERMINISTIC")),
                new PreparedSource(workspaceId, "GIT_HTTPS", "revision"),
                new InMemoryWorkspaceAccess(executionId, workspaceId, Map.of(
                        SampleExecutionEnginePlugin.SOURCE_FILE,
                        "private-source-value".getBytes(StandardCharsets.UTF_8))),
                new InMemoryExecutionSecretAccess(executionId, Map.of(
                        SampleExecutionEnginePlugin.SECRET_NAME,
                        new char[] {'p', 'r', 'i', 'v', 'a', 't', 'e'})));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> plugin.execute(request));
        assertEquals("Sample engine execution failed", failure.getMessage());
    }

    private EngineExecutionContext context(UUID executionId, Map<String, Object> configuration) {
        return new EngineExecutionContext(executionId,
                new EngineIdentity(SampleExecutionEnginePlugin.ENGINE_ID,
                        SampleExecutionEnginePlugin.IMPLEMENTATION_VERSION),
                SampleExecutionEnginePlugin.SOURCE_FILE, configuration,
                "https://example.invalid", Map.of(), Map.of());
    }
}
