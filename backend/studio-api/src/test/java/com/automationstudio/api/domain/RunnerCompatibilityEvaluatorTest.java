package com.automationstudio.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunnerCompatibilityEvaluatorTest {

    private final RunnerCompatibilityEvaluator evaluator = new RunnerCompatibilityEvaluator();
    private final RunnerCapabilities runner = new RunnerCapabilities(
            Map.of(
                    "engines", Map.of("playwright-java", "1.52.0"),
                    "features", List.of("docker", "headless"),
                    "browser", Map.of("name", "chromium")),
            Map.of("region", "eu", "tier", "test"));

    @Test
    void compatibleEngineCapabilitiesAndLabelsAreAccepted() {
        SchedulingRequirements requirements = new SchedulingRequirements(
                "playwright-java",
                Map.of(
                        "features", List.of("docker"),
                        "browser", Map.of("name", "chromium")),
                Map.of("region", "eu"));

        assertThat(evaluator.evaluate(requirements, runner))
                .isEqualTo(CompatibilityResult.COMPATIBLE);
    }

    @Test
    void incompatibleOrMissingEngineIsReported() {
        SchedulingRequirements requirements =
                new SchedulingRequirements("selenium-java", Map.of(), Map.of());

        assertThat(evaluator.evaluate(requirements, runner))
                .isEqualTo(CompatibilityResult.ENGINE_MISMATCH);
    }

    @Test
    void unknownOrMismatchingCapabilityIsReported() {
        SchedulingRequirements unknown = new SchedulingRequirements(
                "playwright-java", Map.of("features", List.of("mobile")), Map.of());
        SchedulingRequirements mismatch = new SchedulingRequirements(
                "playwright-java",
                Map.of("browser", Map.of("name", "firefox")),
                Map.of());

        assertThat(evaluator.evaluate(unknown, runner))
                .isEqualTo(CompatibilityResult.CAPABILITY_MISMATCH);
        assertThat(evaluator.evaluate(mismatch, runner))
                .isEqualTo(CompatibilityResult.CAPABILITY_MISMATCH);
    }

    @Test
    void missingOrMismatchingLabelIsReported() {
        SchedulingRequirements missing = new SchedulingRequirements(
                "playwright-java", Map.of(), Map.of("pool", "private"));
        SchedulingRequirements mismatch = new SchedulingRequirements(
                "playwright-java", Map.of(), Map.of("region", "us"));

        assertThat(evaluator.evaluate(missing, runner))
                .isEqualTo(CompatibilityResult.LABEL_MISMATCH);
        assertThat(evaluator.evaluate(mismatch, runner))
                .isEqualTo(CompatibilityResult.LABEL_MISMATCH);
    }
}
