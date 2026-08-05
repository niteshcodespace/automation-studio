package com.automationstudio.api.execution.engine.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.engine.EngineExecutionRequest;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.EngineExecutionState;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistryImpl;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Repository-level contract inherited by every production execution-engine test fixture.
 *
 * <p>Implementations supply one valid canonical request and, when applicable, verify the
 * resources acquired by that invocation were released. Adding an engine therefore requires
 * opting its normal fixture into the same descriptor, registry, invocation, result, cleanup,
 * and provider-neutrality checks.</p>
 */
public interface ExecutionEngineConformanceContract {

    ExecutionEngine conformanceEngine();

    EngineExecutionRequest conformanceRequest();

    EngineExecutionState conformanceExpectedState();

    default void verifyConformanceCleanup() {
        // Engines that acquire no resources have no cleanup observation to expose.
    }

    void verifyConformanceConcurrency() throws Exception;

    @Test
    default void conformsToImmutableDescriptorAndExactRegistryResolution() {
        ExecutionEngine engine = conformanceEngine();
        var descriptor = engine.descriptor();

        assertThat(descriptor.engineId()).isNotBlank();
        assertThat(descriptor.implementationVersion()).isNotBlank();
        assertThat(descriptor.displayName()).isNotBlank();
        assertThatThrownBy(() -> descriptor.supportedCapabilities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> descriptor.supportedFeatures().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        var registry = new ExecutionEngineRegistryImpl(List.of(engine));
        assertThat(registry.resolve(descriptor.engineId(), descriptor.implementationVersion()).engine())
                .isSameAs(engine);
        assertThat(registry.supportedEngines()).containsExactly(descriptor);
    }

    @Test
    default void conformsToCanonicalInvocationResultAndCleanupContract() {
        ExecutionEngine engine = conformanceEngine();
        EngineExecutionRequest request = conformanceRequest();

        request.validateFor(engine.descriptor());
        EngineExecutionResult result = engine.execute(request);

        assertThat(result.validateFor(request, engine.descriptor())).isSameAs(result);
        assertThat(result.state()).isEqualTo(conformanceExpectedState());
        assertThat(result.executionId()).isEqualTo(request.executionId());
        assertThat(result.workspaceId())
                .isEqualTo(request.preparation().workspace().workspaceId());
        assertThat(result.resolvedRevision())
                .isEqualTo(request.preparation().source().resolvedRevision());
        verifyConformanceCleanup();
    }

    @Test
    default void conformsToSideEffectFreeValidationContract() {
        EngineExecutionRequest request = conformanceRequest();
        var context = request.context();
        var preparation = request.preparation();

        conformanceEngine().validate(context);

        assertThat(request.context()).isSameAs(context);
        assertThat(request.preparation()).isSameAs(preparation);
    }

    @Test
    default void conformsToConcurrentInvocationIsolation() throws Exception {
        verifyConformanceConcurrency();
    }

    @Test
    default void conformsToLeastAuthorityAndProviderNeutralBoundary() throws Exception {
        Method canonicalExecute = conformanceEngine().getClass()
                .getMethod("execute", EngineExecutionRequest.class);

        assertThat(canonicalExecute.getReturnType()).isEqualTo(EngineExecutionResult.class);
        assertThat(conformanceEngine().getClass().getDeclaredFields())
                .noneMatch(field -> Path.class.isAssignableFrom(field.getType()));
        assertThat(EngineExecutionRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("context", "preparation", "secretAccess");
    }
}
