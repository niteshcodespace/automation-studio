package com.automationstudio.api.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import com.automationstudio.api.execution.secret.ExecutionSecretAccess;
import com.automationstudio.api.execution.secret.ResolvedSecret;
import com.automationstudio.api.execution.secret.SecretResolutionException;
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

    private Fixture fixture() {
        UUID executionId = UUID.randomUUID();
        ExecutionContext context = mock(ExecutionContext.class);
        SourcePreparationResult preparation = mock(SourcePreparationResult.class);
        when(context.executionId()).thenReturn(executionId);
        when(preparation.executionId()).thenReturn(executionId);
        when(preparation.state()).thenReturn(SourcePreparationState.PREPARED);
        return new Fixture(executionId, context, preparation);
    }

    private record Fixture(
            UUID executionId,
            ExecutionContext context,
            SourcePreparationResult preparation) { }
}
