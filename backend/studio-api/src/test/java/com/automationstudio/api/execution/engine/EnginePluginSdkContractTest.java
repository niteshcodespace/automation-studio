package com.automationstudio.api.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.ArtifactPublication;
import com.automationstudio.engine.sdk.ArtifactPublicationException;
import com.automationstudio.engine.sdk.ArtifactPublisher;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.PreparedSourceAccess;
import com.automationstudio.engine.sdk.ResolvedSecret;
import com.automationstudio.engine.sdk.WorkspaceAccess;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnginePluginSdkContractTest {

    @Test
    void preservesExactCaseSensitiveIdentityAndDescriptorOrdering() {
        var upper = new EngineIdentity("Engine", "V1");
        assertThat(upper).isNotEqualTo(new EngineIdentity("engine", "v1"));
        var descriptor = new ExecutionEngineDescriptor(
                "Engine", "V1", "Engine", Set.of("z", "a"), Set.of("two", "one"));
        assertThat(descriptor.supportedCapabilities()).containsExactly("a", "z");
        assertThat(descriptor.supportedFeatures()).containsExactly("one", "two");
    }

    @Test
    void recursivelyFreezesProjectedConfiguration() {
        var nested = new ArrayList<>(List.of("one"));
        var configuration = new LinkedHashMap<String, Object>();
        configuration.put("nested", nested);
        var context = context(configuration);
        nested.add("two");
        assertThat(context.suiteConfiguration().get("nested"))
                .isEqualTo(List.of("one"));
        assertThatThrownBy(() -> context.suiteConfiguration().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void correlatesRequestCapabilitiesAndResultIdentity() {
        UUID executionId = UUID.randomUUID();
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        EngineExecutionContext context = context(executionId, Map.of());
        PreparedSource source = new PreparedSource(workspaceId, "GIT", "revision");
        WorkspaceAccess workspace = new WorkspaceAccess() {
            public UUID executionId() { return executionId; }
            public WorkspaceId workspaceId() { return workspaceId; }
            public PreparedSourceAccess openPreparedSource() { throw new UnsupportedOperationException(); }
        };
        var secrets = new com.automationstudio.engine.sdk.ExecutionSecretAccess() {
            public UUID executionId() { return executionId; }
            public ResolvedSecret resolve(String logicalName) { throw new UnsupportedOperationException(); }
        };
        var request = new EngineExecutionRequest(context, source, workspace, secrets);
        assertThat(request.artifactPublisher().executionId()).isEqualTo(executionId);
        assertThatThrownBy(() -> request.artifactPublisher().publish(
                new ArtifactPublication(com.automationstudio.engine.sdk.ArtifactCategory.LOG,
                        "engine.log", "text/plain", Map.of(), output -> {})))
                .isInstanceOf(ArtifactPublicationException.class)
                .hasMessage("Artifact publication is unavailable for this execution");
        var descriptor = new ExecutionEngineDescriptor(
                "Engine", "V1", "Engine", Set.of(), Set.of());
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        var result = new EngineExecutionResult(executionId, "Engine", "V1", workspaceId,
                "revision", EngineExecutionState.SUCCEEDED, now, now, Duration.ZERO);
        assertThat(result.validateFor(request, descriptor)).isSameAs(result);
        assertThatThrownBy(() -> new EngineExecutionRequest(
                context(UUID.randomUUID(), Map.of()), source, workspace, secrets))
                .isInstanceOf(IllegalArgumentException.class);
        ArtifactPublisher mismatchedPublisher = ArtifactPublisher.unavailable(UUID.randomUUID());
        assertThatThrownBy(() -> new EngineExecutionRequest(
                context, source, workspace, secrets, mismatchedPublisher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Engine request identities are inconsistent");
    }

    @Test
    void secretValuesAreDefensiveClearableAndRedacted() {
        char[] source = "canary".toCharArray();
        ResolvedSecret secret = ResolvedSecret.from(source);
        source[0] = 'X';
        var observed = new StringBuilder();
        secret.withValue(value -> observed.append(value));
        assertThat(observed).hasToString("canary");
        assertThat(secret.toString()).doesNotContain("canary");
        secret.close();
        assertThatThrownBy(() -> secret.withValue(value -> { }))
                .isInstanceOf(com.automationstudio.engine.sdk.SecretResolutionException.class);
    }

    private static EngineExecutionContext context(Map<String, Object> configuration) {
        return context(UUID.randomUUID(), configuration);
    }

    private static EngineExecutionContext context(
            UUID executionId, Map<String, Object> configuration) {
        return new EngineExecutionContext(executionId, new EngineIdentity("Engine", "V1"),
                "suite.json", configuration, "https://example.test", Map.of(), Map.of());
    }
}
