package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineIdentity;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SeleniumEnginePluginTest {
    private final SeleniumEnginePlugin plugin = new SeleniumEnginePlugin();

    @Test void exposesExactPassiveDescriptor() {
        var descriptor = plugin.descriptor();
        assertEquals("selenium-java", descriptor.engineId());
        assertEquals("1.0.0", descriptor.implementationVersion());
        assertEquals("Selenium Java Engine", descriptor.displayName());
        assertEquals(java.util.Set.of("prepared-source", "selenium-manifest"), descriptor.supportedCapabilities());
        assertEquals(java.util.Set.of("bounded-manifest-validation", "browser-inert", "strict-configuration"), descriptor.supportedFeatures());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.supportedFeatures().clear());
    }

    @Test void validationIsDeterministicAndResourceFree() {
        var fixture = SeleniumTestFixtures.request(1);
        plugin.validate(fixture.request().context());
        plugin.validate(fixture.request().context());
        assertEquals(0, fixture.workspace().openedHandleCount());
        assertEquals(0, fixture.control().registrations());
    }

    @Test void rejectsIdentityMismatchAndEnvironmentRuntimeAuthority() {
        var fixture = SeleniumTestFixtures.request(2);
        EngineExecutionContext context = fixture.request().context();
        assertThrows(SeleniumEngineException.class, () -> plugin.validate(new EngineExecutionContext(
                context.executionId(), new EngineIdentity("other", "1.0.0"),
                context.suiteReference(), Map.of(), context.environmentBaseUrl(), Map.of(), Map.of())));
        assertThrows(SeleniumEngineException.class, () -> plugin.validate(new EngineExecutionContext(
                context.executionId(), context.engineIdentity(), context.suiteReference(), Map.of(),
                context.environmentBaseUrl(), Map.of("proxy", "unsafe"), Map.of())));
    }

    @Test void validPassiveManifestReachesOnlyInertRuntimeAndClosesSource() {
        var fixture = SeleniumTestFixtures.request(3);
        SeleniumEngineException failure = assertThrows(
                SeleniumEngineException.class, () -> plugin.execute(fixture.request()));
        assertEquals("SELENIUM_RUNTIME_NOT_AVAILABLE", failure.code());
        assertEquals("Selenium runtime is not available", failure.getMessage());
        assertEquals(1, fixture.workspace().openedHandleCount());
        assertEquals(1, fixture.workspace().closedHandleCount());
        assertEquals(0, fixture.control().registrations());
    }

    @Test void injectedInertSeamReceivesValidatedContractWithoutFabricatingResult() {
        AtomicInteger calls = new AtomicInteger();
        var provider = new SeleniumEnginePlugin(
                new com.automationstudio.engine.selenium.manifest.SeleniumManifestParser(),
                (manifest, configuration) -> calls.incrementAndGet());
        var fixture = SeleniumTestFixtures.request(4);
        assertEquals("Selenium runtime is not available", assertThrows(
                SeleniumEngineException.class, () -> provider.execute(fixture.request())).getMessage());
        assertEquals(1, calls.get());
        assertEquals(0, fixture.control().registrations());
    }

    @Test void diagnosticsAreSanitized() {
        var fixture = SeleniumTestFixtures.request(5);
        assertTrue(fixture.request().toString().contains("REDACTED"));
        assertFalse(fixture.request().toString().contains("selenium.json"));
        String message = assertThrows(
                SeleniumEngineException.class, () -> plugin.execute(fixture.request())).getMessage();
        for (String sensitive : java.util.List.of("#password", "alice", "/login", "password")) {
            assertFalse(message.contains(sensitive));
        }
    }
}
