package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeleniumSuiteConfigurationTest {
    @Test void appliesExactDefaults() {
        assertEquals(
                new SeleniumSuiteConfiguration("chrome", true, 30_000, 30_000,
                        1_280, 720, "same-origin"), SeleniumSuiteConfiguration.parse(Map.of()));
    }

    @Test void acceptsEveryInclusiveBoundary() {
        assertEquals(100, SeleniumSuiteConfiguration.parse(Map.of(
                "actionTimeoutMs", 100, "pageLoadTimeoutMs", 100,
                "viewportWidth", 320, "viewportHeight", 200)).actionTimeoutMs());
        assertEquals(3_840, SeleniumSuiteConfiguration.parse(Map.of(
                "actionTimeoutMs", 120_000L, "pageLoadTimeoutMs", BigInteger.valueOf(300_000),
                "viewportWidth", 3_840, "viewportHeight", 2_160)).viewportWidth());
    }

    @Test void rejectsWrongTypesValuesAndAuthorityBearingKeys() {
        List<Map<String, Object>> invalid = List.of(
                Map.of("browser", "firefox"), Map.of("headless", false),
                Map.of("actionTimeoutMs", 99), Map.of("pageLoadTimeoutMs", 300_001),
                Map.of("viewportWidth", 319), Map.of("viewportHeight", 2_161),
                Map.of("navigationPolicy", "any-origin"), Map.of("actionTimeoutMs", 1.5),
                Map.of("remoteUrl", "https://grid.invalid"), Map.of("capabilities", Map.of()),
                Map.of("proxy", "localhost"), Map.of("driverExecutable", "driver"),
                Map.of("browserExecutable", "browser"), Map.of("downloadDirectory", "out"),
                Map.of("environmentVariables", Map.of()), Map.of("systemProperties", Map.of()),
                Map.of("jvmArgs", List.of()), Map.of("parallelism", 2),
                Map.of("captureFailureReport", true), Map.of("apiToken", "redacted"));
        invalid.forEach(configuration -> assertEquals("Selenium suite configuration is invalid",
                assertThrows(SeleniumEngineException.class,
                        () -> SeleniumSuiteConfiguration.parse(configuration)).getMessage()));
    }
}
