package com.automationstudio.engine.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.util.HashSet;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/** JUnit 5 contract that exercises a plugin directly, without a platform registry or services. */
public interface ExecutionEnginePluginConformanceContract {

    ExecutionEnginePluginFixture fixture();

    @Test
    default void exposesCompleteImmutableDescriptor() {
        ExecutionEngineDescriptor descriptor = fixture().plugin().descriptor();
        assertFalse(descriptor.engineId().isBlank());
        assertFalse(descriptor.implementationVersion().isBlank());
        assertFalse(descriptor.displayName().isBlank());
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.supportedCapabilities().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.supportedFeatures().clear());
    }

    @Test
    default void validationIsDeterministicAndAcquiresNoExecutionResources() {
        ExecutionEnginePluginFixture supplied = fixture();
        EngineExecutionContext context = supplied.validRequest().context();
        long before = supplied.validationResourceAcquisitions();
        supplied.plugin().validate(context);
        supplied.plugin().validate(context);
        assertEquals(before, supplied.validationResourceAcquisitions());
    }

    @Test
    default void rejectsRequestForDifferentExactIdentity() {
        ExecutionEnginePluginFixture supplied = fixture();
        EngineExecutionRequest request = supplied.validRequest();
        EngineExecutionContext context = request.context();
        var mismatch = new EngineExecutionContext(context.executionId(),
                new EngineIdentity(context.engineIdentity().engineId(), "different-version"),
                context.suiteReference(), context.suiteConfiguration(), context.environmentBaseUrl(),
                context.environmentConfiguration(), context.variables());
        var mismatchedRequest = new EngineExecutionRequest(mismatch, request.preparedSource(),
                request.workspaceAccess(), request.secretAccess(), request.artifactPublisher(),
                request.executionControl());
        assertThrows(IllegalArgumentException.class,
                () -> mismatchedRequest.validateFor(supplied.plugin().descriptor()));
    }

    @Test
    default void returnsCanonicalCorrelatedResultAndCleansUp() {
        ExecutionEnginePluginFixture supplied = fixture();
        EngineExecutionRequest request = supplied.validRequest();
        ExecutionEnginePlugin plugin = supplied.plugin();
        request.validateFor(plugin.descriptor());
        ExecutionEnginePluginFixture.ExecutionFailureExpectation expectedFailure =
                supplied.expectedExecutionFailure();
        if (expectedFailure != null) {
            Throwable failure = assertThrows(expectedFailure.type(), () -> plugin.execute(request));
            expectedFailure.assertIdentity(failure);
            assertTrue(supplied.cleanupObserved(request.executionId()));
            return;
        }
        EngineExecutionResult result = plugin.execute(request);
        assertSame(result, result.validateFor(request, plugin.descriptor()));
        assertEquals(supplied.expectedState(), result.state());
        assertEquals(request.executionId(), result.executionId());
        assertEquals(request.preparedSource().workspaceId(), result.workspaceId());
        assertEquals(request.preparedSource().resolvedRevision(), result.resolvedRevision());
        assertFalse(result.finishedAt().isBefore(result.startedAt()));
        assertEquals(result.duration(), java.time.Duration.between(result.startedAt(), result.finishedAt()));
        assertTrue(supplied.cleanupObserved(request.executionId()));
    }

    @Test
    default void exposesProviderNeutralSignaturesAndRedactedRequestDiagnostics() throws Exception {
        ExecutionEnginePlugin plugin = fixture().plugin();
        assertEquals(EngineExecutionResult.class,
                plugin.getClass().getMethod("execute", EngineExecutionRequest.class).getReturnType());
        assertEquals(List.of("context", "preparedSource", "workspaceAccess", "secretAccess",
                        "artifactPublisher", "executionControl"),
                List.of(EngineExecutionRequest.class.getRecordComponents()).stream()
                        .map(component -> component.getName()).toList());
        assertTrue(fixture().validRequest().toString().contains("REDACTED"));
        assertNotNull(fixture().validRequest().executionControl());
        for (var field : plugin.getClass().getDeclaredFields()) {
            assertFalse(Path.class.isAssignableFrom(field.getType()),
                    "Plugins must consume bounded workspace capability, not physical paths");
        }
    }

    @Test
    default void isolatesConcurrentInvocations() throws Exception {
        ExecutionEnginePluginFixture supplied = fixture();
        List<EngineExecutionRequest> requests = supplied.concurrentRequests();
        assertTrue(requests.size() >= 2, "Conformance requires at least two concurrent requests");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<EngineExecutionResult>> futures = requests.stream()
                    .map(request -> executor.submit(() -> supplied.plugin().execute(
                            request.validateFor(supplied.plugin().descriptor()))))
                    .toList();
            var executionIds = new HashSet<>();
            for (int index = 0; index < requests.size(); index++) {
                ExecutionEnginePluginFixture.ExecutionFailureExpectation expectedFailure =
                        supplied.expectedExecutionFailure();
                if (expectedFailure != null) {
                    int requestIndex = index;
                    ExecutionException failure = assertThrows(
                            ExecutionException.class, () -> futures.get(requestIndex).get());
                    assertTrue(expectedFailure.type().isInstance(failure.getCause()));
                    expectedFailure.assertIdentity(failure.getCause());
                    assertTrue(supplied.cleanupObserved(requests.get(index).executionId()));
                    continue;
                }
                EngineExecutionResult result = futures.get(index).get();
                assertNotNull(result.validateFor(requests.get(index), supplied.plugin().descriptor()));
                assertTrue(executionIds.add(result.executionId()), "Execution identity was reused");
                assertTrue(supplied.cleanupObserved(result.executionId()));
            }
        }
    }
}
