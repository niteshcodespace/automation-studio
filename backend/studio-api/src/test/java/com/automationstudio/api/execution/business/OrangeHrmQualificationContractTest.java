package com.automationstudio.api.execution.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.EngineExecutionRequest;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.ExecutionEngineNotFoundException;
import com.automationstudio.api.execution.engine.playwright.PlaywrightEngineDescriptor;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrangeHrmQualificationContractTest {

    private static final String SECRET_CANARY = "qualification-secret-canary";

    @Test
    void defaultVerificationIsInertBeforeBrowserOrSecretAccess() {
        AtomicInteger credentialChecks = new AtomicInteger();
        AtomicInteger executableChecks = new AtomicInteger();

        var decision = OrangeHrmQualificationPrerequisites.evaluate(
                Map.of(), key -> { credentialChecks.incrementAndGet(); return true; },
                path -> { executableChecks.incrementAndGet(); return true; });

        assertThat(decision.ready()).isFalse();
        assertThat(decision.reason()).isEqualTo("AS-025G qualification is not explicitly enabled");
        assertThat(credentialChecks).hasValue(0);
        assertThat(executableChecks).hasValue(0);
    }

    @Test
    void qualificationRegistryResolvesTheSameExactPlaywrightEngineInstance() {
        ExecutionEngine engine = new DescriptorOnlyEngine(PlaywrightEngineDescriptor.descriptor());

        var qualificationEngine = new OrangeHrmQualificationExecutionEngine();
        qualificationEngine.bind(engine);
        var registry = new com.automationstudio.api.execution.engine.ExecutionEngineRegistryImpl(
                java.util.List.of(qualificationEngine));

        assertThat(registry.resolve("playwright-java", "1.61.0").engine())
                .isSameAs(qualificationEngine);
        assertThat(qualificationEngine.isBoundTo(engine)).isTrue();
        assertThatThrownBy(() -> qualificationEngine.bind(engine))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AS-025G execution engine is already bound");
    }

    @Test
    void qualificationRegistryFailsClosedForMissingOrIncompatibleEngine() {
        ExecutionEngine engine = new DescriptorOnlyEngine(PlaywrightEngineDescriptor.descriptor());
        var qualificationEngine = new OrangeHrmQualificationExecutionEngine();
        qualificationEngine.bind(engine);
        var registry = new com.automationstudio.api.execution.engine.ExecutionEngineRegistryImpl(
                java.util.List.of(qualificationEngine));

        assertThatThrownBy(() -> registry.resolve("missing", "1.61.0"))
                .isInstanceOf(ExecutionEngineNotFoundException.class)
                .hasMessage("Execution engine was not found");
        assertThatThrownBy(() -> new OrangeHrmQualificationExecutionEngine().bind(
                new DescriptorOnlyEngine(new ExecutionEngineDescriptor(
                        "playwright-java", "wrong", "Wrong", java.util.Set.of(),
                        java.util.Set.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AS-025G requires the exact Playwright engine");
        assertThatThrownBy(() -> new OrangeHrmQualificationExecutionEngine().validate(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AS-025G execution engine is not bound");
    }

    @Test
    void missingBrowserSkipsWithSanitizedReason() {
        var decision = evaluate(properties(Map.of()), key -> true, path -> false);
        assertSkip(decision, "AS-025G Chromium executable is unavailable");
    }

    @Test
    void missingProviderSkipsWithSanitizedReason() {
        var values = properties(Map.of(OrangeHrmQualificationPrerequisites.PROVIDER_ENABLED, "false"));
        var decision = evaluate(values, key -> true, path -> true);
        assertSkip(decision, "AS-025G secret provider is unavailable");
    }

    @Test
    void missingCredentialsSkipsWithoutNamingCredential() {
        var decision = evaluate(properties(Map.of()), key -> false, path -> true);
        assertSkip(decision, "AS-025G operator credentials are unavailable");
        assertThat(decision.toString()).doesNotContain(
                OrangeHrmQualificationPrerequisites.USERNAME_KEY,
                OrangeHrmQualificationPrerequisites.PASSWORD_KEY, SECRET_CANARY);
    }

    @Test
    void officialPublicDemoOriginIsTheOnlyApprovedTarget() {
        Map<String, String> values = properties(Map.of(
                OrangeHrmQualificationPrerequisites.TARGET_URL,
                "https://opensource-demo.orangehrmlive.com"));
        var decision = evaluate(values, key -> true, path -> true);
        assertThat(decision.ready()).isTrue();

        for (String target : new String[] {
                "https://example.com",
                "https://approved.orangehrm.internal",
                "http://opensource-demo.orangehrmlive.com",
                "https://unrecognized.orangehrmlive.com"
        }) {
            assertSkip(evaluate(properties(Map.of(
                            OrangeHrmQualificationPrerequisites.TARGET_URL, target)),
                            key -> true, path -> true),
                    "AS-025G approved non-production target is unavailable");
        }
    }

    @Test
    void targetMustBeCanonicalOriginWithoutPathOrTrailingDot() {
        for (String target : new String[] {
                "https://approved.orangehrm.internal/",
                "https://approved.orangehrm.internal/base",
                "https://opensource-demo.orangehrmlive.com.",
                "https://opensource-demo.orangehrmlive.com:443",
                "https://OPENSOURCE-DEMO.ORANGEHRMLIVE.COM"
        }) {
            var decision = evaluate(properties(Map.of(
                    OrangeHrmQualificationPrerequisites.TARGET_URL, target)),
                    key -> true, path -> true);
            assertSkip(decision, "AS-025G approved non-production target is unavailable");
        }
    }

    @Test
    void completeConfigurationUsesExplicitOperatorInputsOnly() {
        AtomicInteger credentialChecks = new AtomicInteger();
        var decision = evaluate(properties(Map.of()), key -> {
            credentialChecks.incrementAndGet();
            return true;
        }, path -> true);
        assertThat(decision.ready()).isTrue();
        assertThat(credentialChecks).hasValue(2);
        assertThat(decision.configuration().targetClassification()).isEqualTo("NON_PRODUCTION");
        assertThat(decision.configuration().toString()).isEqualTo(
                "OrangeHrmQualificationConfiguration[REDACTED]");
        assertThat(decision.toString()).doesNotContain(SECRET_CANARY, "approved.orangehrm.internal");
    }

    @Test
    void evidenceContainsOnlyBoundedSanitizedQualificationFields() {
        var evidence = new OrangeHrmQualificationEvidence(
                "Windows 11", "amd64", "21.0.8", "1.61.0", "Chromium", "140.0.1",
                "operator-provisioned", "NON_PRODUCTION",
                "61ed74692f7d39bb4a2ae7e505a08b72394a9c6b", 1, 0, 0);
        assertThat(evidence.toString()).contains("passed=1", "failed=0", "errors=0")
                .doesNotContain("http", "username", "password", "AUTOMATION_SECRET", SECRET_CANARY);
        assertThatThrownBy(() -> new OrangeHrmQualificationEvidence(
                "Windows", "amd64", "21", "1.61.0", "Chromium", "140",
                "C:/secret?token=value", "NON_PRODUCTION", "revision", 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Qualification evidence is invalid");
    }

    private static OrangeHrmQualificationPrerequisites.Decision evaluate(
            Map<String, String> properties,
            java.util.function.Predicate<String> credentialPresent,
            java.util.function.Predicate<Path> executable) {
        return OrangeHrmQualificationPrerequisites.evaluate(
                properties, credentialPresent, executable);
    }

    private static Map<String, String> properties(Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>();
        values.put(OrangeHrmQualificationPrerequisites.ENABLED, "true");
        values.put(OrangeHrmQualificationPrerequisites.BROWSER_EXECUTABLE,
                Path.of("C:/operator/chromium.exe").toString());
        values.put(OrangeHrmQualificationPrerequisites.PROVIDER_ENABLED, "true");
        values.put(OrangeHrmQualificationPrerequisites.TARGET_URL,
                "https://opensource-demo.orangehrmlive.com");
        values.put(OrangeHrmQualificationPrerequisites.TARGET_CLASSIFICATION, "NON_PRODUCTION");
        values.put(OrangeHrmQualificationPrerequisites.BROWSER_PRODUCT, "Chromium");
        values.put(OrangeHrmQualificationPrerequisites.BROWSER_BUILD, "operator-qualified");
        values.putAll(overrides);
        return Map.copyOf(values);
    }

    private static void assertSkip(
            OrangeHrmQualificationPrerequisites.Decision decision, String reason) {
        assertThat(decision.ready()).isFalse();
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.reason()).doesNotContain(
                SECRET_CANARY, "approved.orangehrm.internal", "AUTOMATION_SECRET");
    }

    private record DescriptorOnlyEngine(ExecutionEngineDescriptor descriptor)
            implements ExecutionEngine {
        @Override
        public void validate(ExecutionContext context) {
        }

        @Override
        public EngineExecutionResult execute(EngineExecutionRequest request) {
            throw new AssertionError("Focused registry tests must not launch a browser");
        }
    }
}
