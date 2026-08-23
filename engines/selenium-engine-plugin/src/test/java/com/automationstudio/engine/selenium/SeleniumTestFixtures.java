package com.automationstudio.engine.selenium;

import com.automationstudio.engine.conformance.InMemoryArtifactPublisher;
import com.automationstudio.engine.conformance.InMemoryExecutionSecretAccess;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineIdentity;
import com.automationstudio.engine.sdk.ExecutionControl;
import com.automationstudio.engine.sdk.ExecutionTeardown;
import com.automationstudio.engine.sdk.PreparedSource;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class SeleniumTestFixtures {
    static final String REFERENCE = "selenium.json";

    public static String validManifest() {
        return """
                {"schemaVersion":"1.0","name":"Passive suite","scenarios":[
                  {"id":"scenario-1","name":"Scenario one","steps":[
                    {"id":"step-1","action":"navigation","url":"/login"},
                    {"id":"step-2","action":"click","selector":"#login"},
                    {"id":"step-3","action":"fill","selector":"#user","value":"alice"},
                    {"id":"step-4","action":"sensitive-fill","selector":"#password","secretRef":"password"},
                    {"id":"step-5","action":"wait-visible","selector":"#home","timeoutMs":1000},
                    {"id":"step-6","action":"assert-visible","selector":"#home"},
                    {"id":"step-7","action":"assert-text","selector":"h1","expected":"Home"},
                    {"id":"step-8","action":"assert-url","expected":"/home"}
                  ]}
                ]}
                """;
    }

    public static Fixture request(long seed) {
        UUID executionId = new UUID(31, seed);
        WorkspaceId workspaceId = new WorkspaceId(new UUID(310, seed));
        var context = new EngineExecutionContext(executionId,
                new EngineIdentity(SeleniumEnginePlugin.ENGINE_ID,
                        SeleniumEnginePlugin.IMPLEMENTATION_VERSION),
                REFERENCE, Map.of(), "https://example.invalid", Map.of(), Map.of());
        var workspace = new InMemoryWorkspaceAccess(executionId, workspaceId, Map.of(
                REFERENCE, validManifest().getBytes(StandardCharsets.UTF_8)));
        var control = new TrackingControl();
        var request = new EngineExecutionRequest(context,
                new PreparedSource(workspaceId, "GIT_HTTPS", "revision-" + seed), workspace,
                new InMemoryExecutionSecretAccess(executionId, Map.of()),
                new InMemoryArtifactPublisher(executionId), control);
        return new Fixture(request, workspace, control);
    }

    public record Fixture(EngineExecutionRequest request, InMemoryWorkspaceAccess workspace,
                          TrackingControl control) {}

    public static final class TrackingControl implements ExecutionControl {
        private final AtomicInteger registrations = new AtomicInteger();
        @Override public Instant deadline() { return Instant.parse("2026-08-22T12:00:00Z"); }
        @Override public Duration remainingTime() { return Duration.ofMinutes(1); }
        @Override public boolean isExpired() { return false; }
        @Override public boolean cancellationRequested() { return false; }
        @Override public void registerTeardown(ExecutionTeardown teardown) { registrations.incrementAndGet(); }
        @Override public boolean isBounded() { return true; }
        public int registrations() { return registrations.get(); }
    }

    private SeleniumTestFixtures() {}
}
