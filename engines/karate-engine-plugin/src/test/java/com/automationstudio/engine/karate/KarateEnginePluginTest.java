package com.automationstudio.engine.karate;

import static org.junit.jupiter.api.Assertions.*;
import com.automationstudio.engine.conformance.InMemoryExecutionSecretAccess;
import com.automationstudio.engine.sdk.EngineExecutionState;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KarateEnginePluginTest {
    @Test void exposesStableIdentityAndPerformsStructuralDiscoveryOnly() {
        var request = KarateTestFixtures.request(1, KarateTestFixtures.features("features/a.feature"), null);
        var plugin = plugin();
        assertEquals("karate", plugin.descriptor().engineId());
        assertEquals("1.5.2", plugin.descriptor().implementationVersion());
        assertEquals(EngineExecutionState.SUCCEEDED, plugin.execute(request).state());
        assertEquals(0, ((InMemoryExecutionSecretAccess) request.secretAccess()).resolutionCount());
    }

    @Test void closesPreparedSourceAfterSuccessfulDiscovery() {
        var observed = new com.automationstudio.engine.conformance.InMemoryWorkspaceAccess[1];
        var request = KarateTestFixtures.request(4, KarateTestFixtures.features("features/a.feature"), observed);
        plugin().execute(request);
        assertEquals(1, observed[0].openedHandleCount());
        assertEquals(1, observed[0].closedHandleCount());
    }

    @Test void validationDoesNotAcquirePreparedSource() {
        var observed = new com.automationstudio.engine.conformance.InMemoryWorkspaceAccess[1];
        var request = KarateTestFixtures.request(2, KarateTestFixtures.features("features/a.feature"), observed);
        plugin().validate(request.context());
        assertEquals(0, observed[0].openedHandleCount());
    }

    @Test void sanitizesInvalidRequests() {
        var request = KarateTestFixtures.request(3, KarateTestFixtures.features("features/a.feature"), null);
        var badContext = new com.automationstudio.engine.sdk.EngineExecutionContext(request.executionId(),
                request.context().engineIdentity(), "features", Map.of("schemaVersion", "1", "featureRoot", "../secret"),
                "https://example.invalid", Map.of(), Map.of());
        var exception = assertThrows(KarateEngineException.class, () -> plugin().validate(badContext));
        assertEquals("INVALID_FEATURE_ROOT", exception.code());
        assertNull(exception.getCause());
    }

    private static KarateEnginePlugin plugin() {
        return new KarateEnginePlugin(java.time.Clock.systemUTC(), new KarateFeatureDiscovery(), (id, source, projected, features, configuration, variables, base, secrets) -> new KarateWorkerRuntime.WorkerExecutionResult("SUCCEEDED",1,1,1,0,"NONE"));
    }
}
