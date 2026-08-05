package com.automationstudio.api.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import com.automationstudio.api.execution.secret.ExecutionSecretAccess;
import com.automationstudio.api.execution.secret.ResolvedSecret;
import com.automationstudio.api.execution.secret.SecretResolutionException;
import com.automationstudio.api.execution.workspace.WorkspaceDescriptor;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.WorkspaceMetadata;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EngineExecutionRequestSecretAccessTest {

    @Test
    void carriesOnlyExecutionMatchedNarrowSecretAccess() {
        Fixture fixture = fixture();
        ExecutionSecretAccess access = mock(ExecutionSecretAccess.class);
        when(access.executionId()).thenReturn(fixture.executionId());

        EngineExecutionRequest request = new EngineExecutionRequest(
                fixture.context(), fixture.preparation(), access);

        assertThat(request.secretAccess()).isSameAs(access);
        assertThat(ExecutionSecretAccess.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("executionId", "resolve", "unavailable");
    }

    @Test
    void rejectsNullOrExecutionMismatchedAccess() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> new EngineExecutionRequest(
                        fixture.context(), fixture.preparation(), null))
                .isInstanceOf(NullPointerException.class);
        ExecutionSecretAccess mismatched = mock(ExecutionSecretAccess.class);
        when(mismatched.executionId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> new EngineExecutionRequest(
                        fixture.context(), fixture.preparation(), mismatched))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPreparationBelongingToAnotherExecution() {
        Fixture fixture = fixture();
        SourcePreparationResult mismatched = mock(SourcePreparationResult.class);
        when(mismatched.state()).thenReturn(SourcePreparationState.PREPARED);
        when(mismatched.executionId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> new EngineExecutionRequest(
                        fixture.context(), mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Execution context and preparation must identify the same execution");
    }

    @Test
    void legacyConstructorProvidesFixedFailClosedCapability() {
        Fixture fixture = fixture();
        EngineExecutionRequest request = new EngineExecutionRequest(
                fixture.context(), fixture.preparation());

        assertThat(request.secretAccess().executionId()).isEqualTo(fixture.executionId());
        assertThat(request.secretAccess().toString())
                .isEqualTo("ExecutionSecretAccess[UNAVAILABLE]");
        assertThatThrownBy(() -> request.secretAccess().resolve("logical.name"))
                .isInstanceOfSatisfying(
                        SecretResolutionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("SECRET_CAPABILITY_UNAVAILABLE"))
                .hasMessage("Secret capability is unavailable")
                .hasNoCause();
    }

    @Test
    void toStringDoesNotRenderSecretAccessRepresentation() {
        Fixture fixture = fixture();
        String canary = "secret-capability-canary";
        ExecutionSecretAccess sensitiveAccess = new ExecutionSecretAccess() {
            @Override
            public UUID executionId() {
                return fixture.executionId();
            }

            @Override
            public ResolvedSecret resolve(String logicalName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String toString() {
                return canary;
            }
        };

        EngineExecutionRequest request = new EngineExecutionRequest(
                fixture.context(), fixture.preparation(), sensitiveAccess);

        assertThat(request.toString())
                .doesNotContain(canary)
                .contains("secretAccess=REDACTED");
    }

    @Test
    void validatesExactCanonicalEngineIdentityAndRetainsReadOnlyAliases() {
        Fixture fixture = fixture();
        EngineExecutionRequest request = new EngineExecutionRequest(
                fixture.context(), fixture.preparation());
        ExecutionEngineDescriptor descriptor = new ExecutionEngineDescriptor(
                "engine", "1.0", "Engine", java.util.Set.of(), java.util.Set.of());

        assertThat(request.validateFor(descriptor)).isSameAs(request);
        assertThat(request.engineId()).isEqualTo("engine");
        assertThat(request.implementationVersion()).isEqualTo("1.0");
        assertThat(request.engineName()).isEqualTo(request.engineId());
        assertThat(request.engineVersion()).isEqualTo(request.implementationVersion());
        assertThatThrownBy(() -> request.validateFor(new ExecutionEngineDescriptor(
                        "ENGINE", "1.0", "Engine", java.util.Set.of(), java.util.Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Execution request does not target the selected engine");
        assertThatThrownBy(() -> request.validateFor(new ExecutionEngineDescriptor(
                        "engine", "1.1", "Engine", java.util.Set.of(), java.util.Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Execution request does not target the selected engine");
    }

    @Test
    void preparedInvocationDoesNotFallBackToLegacyContextInvocation() {
        Fixture fixture = fixture();
        EngineExecutionRequest request = new EngineExecutionRequest(
                fixture.context(), fixture.preparation());
        ExecutionEngine legacyOnly = new ExecutionEngine() {
            @Override
            public ExecutionEngineDescriptor descriptor() {
                return new ExecutionEngineDescriptor(
                        "engine", "1.0", "Engine", java.util.Set.of(), java.util.Set.of());
            }

            @Override
            public void validate(ExecutionContext context) {
            }

            @Deprecated(forRemoval = false)
            @Override
            public com.automationstudio.api.execution.lifecycle.ExecutionResult execute(
                    ExecutionContext context) {
                throw new AssertionError("Legacy invocation must not be called");
            }
        };

        assertThatThrownBy(() -> legacyOnly.execute(request))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Prepared execution engine invocation is not implemented");
    }

    @Test
    void requestExposesOnlyProviderNeutralPreparedCapabilities() {
        assertThat(EngineExecutionRequest.class.getRecordComponents())
                .extracting(component -> component.getType().getName())
                .containsExactly(
                        ExecutionContext.class.getName(),
                        SourcePreparationResult.class.getName(),
                        ExecutionSecretAccess.class.getName());
    }

    private Fixture fixture() {
        UUID executionId = UUID.randomUUID();
        ExecutionContext context = mock(ExecutionContext.class);
        ExecutionSuiteSnapshot suite = mock(ExecutionSuiteSnapshot.class);
        SourcePreparationResult preparation = mock(SourcePreparationResult.class);
        WorkspaceDescriptor workspace = mock(WorkspaceDescriptor.class);
        WorkspaceMetadata metadata = mock(WorkspaceMetadata.class);
        ExecutionSourceReference sourceReference = mock(ExecutionSourceReference.class);
        SourceMaterializationResult source = mock(SourceMaterializationResult.class);
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());
        String revision = "0123456789012345678901234567890123456789";
        when(context.executionId()).thenReturn(executionId);
        when(context.suite()).thenReturn(suite);
        when(suite.engineId()).thenReturn("engine");
        when(suite.engineVersion()).thenReturn("1.0");
        when(preparation.executionId()).thenReturn(executionId);
        when(preparation.state()).thenReturn(SourcePreparationState.PREPARED);
        when(preparation.workspace()).thenReturn(workspace);
        when(preparation.source()).thenReturn(source);
        when(workspace.executionId()).thenReturn(executionId);
        when(workspace.workspaceId()).thenReturn(workspaceId);
        when(workspace.state()).thenReturn(WorkspaceState.READY);
        when(workspace.metadata()).thenReturn(metadata);
        when(metadata.sourceReference()).thenReturn(sourceReference);
        when(source.workspaceId()).thenReturn(workspaceId);
        when(source.sourceType()).thenReturn(SourceType.GIT_HTTPS);
        when(source.resolvedRevision()).thenReturn(revision);
        when(sourceReference.sourceType()).thenReturn(SourceType.GIT_HTTPS);
        when(sourceReference.revision()).thenReturn(revision);
        return new Fixture(executionId, context, preparation);
    }

    private record Fixture(
            UUID executionId,
            ExecutionContext context,
            SourcePreparationResult preparation) { }
}
