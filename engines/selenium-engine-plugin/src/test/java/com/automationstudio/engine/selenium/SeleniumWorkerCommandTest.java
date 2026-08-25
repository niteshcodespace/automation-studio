package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeleniumWorkerCommandTest {
    private static final String IMAGE = "registry.example/automation/selenium-worker@sha256:" + "a".repeat(64);
    @Test void requiresDigestAndBuildsOnlyPlatformOwnedArguments() {
        UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        var command = SeleniumWorkerCommand.create(id, IMAGE, SeleniumContainmentLimits.defaults());
        assertEquals("docker", command.getFirst());
        assertTrue(command.contains("--network")); assertTrue(command.contains("none"));
        assertTrue(command.contains("--read-only")); assertTrue(command.contains("--cap-drop"));
        assertFalse(command.contains("--mount")); assertFalse(command.contains("--volume"));
        assertEquals("as-selenium-worker-12345678123412341234123456789abc", SeleniumWorkerCommand.workerName(id));
    }
    @Test void rejectsFloatingAndMalformedImages() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> SeleniumWorkerCommand.create(id, "worker:latest", SeleniumContainmentLimits.defaults()));
        assertThrows(IllegalArgumentException.class, () -> SeleniumWorkerCommand.create(id, "worker@sha256:abc", SeleniumContainmentLimits.defaults()));
        assertThrows(IllegalArgumentException.class, () -> SeleniumWorkerCommand.create(id, "UPPER/worker@sha256:"+"a".repeat(64), SeleniumContainmentLimits.defaults()));
    }
}
