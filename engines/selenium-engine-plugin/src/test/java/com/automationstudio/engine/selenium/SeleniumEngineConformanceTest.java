package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.automationstudio.engine.conformance.ExecutionEnginePluginConformanceContract;
import com.automationstudio.engine.conformance.ExecutionEnginePluginFixture;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

class SeleniumEngineConformanceTest implements ExecutionEnginePluginConformanceContract {
    private final Fixture fixture = new Fixture();
    @Override public ExecutionEnginePluginFixture fixture() { return fixture; }

    @Test void rejectsSameExceptionTypeWithWrongFailureIdentity() {
        assertThrows(AssertionFailedError.class, () -> fixture.expectedExecutionFailure()
                .assertIdentity(new SeleniumEngineException(
                        "INVALID_ENGINE_REQUEST", "Selenium engine request is invalid")));
    }

    private static final class Fixture implements ExecutionEnginePluginFixture {
        private final ExecutionEnginePlugin plugin = new SeleniumEnginePlugin();
        private final Map<UUID, SeleniumTestFixtures.Fixture> observations = new ConcurrentHashMap<>();
        private final List<EngineExecutionRequest> requests = List.of(request(10), request(11), request(12));
        @Override public ExecutionEnginePlugin plugin() { return plugin; }
        @Override public EngineExecutionRequest validRequest() { return requests.getFirst(); }
        @Override public EngineExecutionState expectedState() { return EngineExecutionState.SUCCEEDED; }
        @Override public List<EngineExecutionRequest> concurrentRequests() { return requests.subList(1, 3); }
        @Override public long validationResourceAcquisitions() {
            return observations.values().stream().mapToLong(value ->
                    value.workspace().openedHandleCount() + value.control().registrations()).sum();
        }
        @Override public boolean cleanupObserved(UUID executionId) {
            var observed = observations.get(executionId);
            return observed != null && observed.workspace().openedHandleCount() > 0
                    && observed.workspace().openedHandleCount() == observed.workspace().closedHandleCount()
                    && observed.control().registrations() == 0;
        }
        @Override public ExecutionFailureExpectation expectedExecutionFailure() {
            return new ExecutionFailureExpectation(SeleniumEngineException.class, failure -> {
                SeleniumEngineException seleniumFailure =
                        assertInstanceOf(SeleniumEngineException.class, failure);
                assertEquals("SELENIUM_RUNTIME_NOT_AVAILABLE", seleniumFailure.code());
                assertEquals("Selenium runtime is not available", seleniumFailure.getMessage());
            });
        }
        private EngineExecutionRequest request(long seed) {
            var value = SeleniumTestFixtures.request(seed);
            observations.put(value.request().executionId(), value);
            return value.request();
        }
    }
}
