package com.automationstudio.api.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionEnvironmentSnapshot;
import com.automationstudio.api.execution.ExecutionMetadata;
import com.automationstudio.api.execution.ExecutionRetryPolicy;
import com.automationstudio.api.execution.ExecutionRunnerContext;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ExecutionEngineCompatibilityTest {

    @Test
    void validatesRegistryRunnerAndProviderCompatibilityWithoutMutatingContext() {
        AtomicBoolean validated = new AtomicBoolean();
        ExecutionEngine engine = new ExecutionEngine() {
            @Override
            public ExecutionEngineDescriptor descriptor() {
                return new ExecutionEngineDescriptor(
                        "playwright", "1.52", "Playwright", Set.of("web"), Set.of());
            }

            @Override
            public void validate(ExecutionContext context) {
                validated.set(true);
            }
        };
        ExecutionEngineRegistry registry = new ExecutionEngineRegistryImpl(List.of(engine));
        ExecutionContext context = context(Map.of("engines", Map.of("playwright", "1.52")));

        assertThat(registry.validateCompatibility(context).engine()).isSameAs(engine);
        assertThat(validated).isTrue();
        assertThat(context.suite().engineVersion()).isEqualTo("1.52");
    }

    @Test
    void rejectsRunnerWithoutTheExactEngineVersion() {
        ExecutionEngineRegistry registry = new ExecutionEngineRegistryImpl(
                List.of(ExecutionEngineRegistryTest.engine("playwright", "1.52")));

        assertThatThrownBy(() -> registry.validateCompatibility(
                context(Map.of("engines", Map.of("playwright", "1.51")))))
                .isInstanceOf(ExecutionEngineCompatibilityException.class);
        assertThatThrownBy(() -> registry.validateCompatibility(context(Map.of())))
                .isInstanceOf(ExecutionEngineCompatibilityException.class);
    }

    private ExecutionContext context(Map<String, Object> capabilities) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-29T10:00:00Z");
        return new ExecutionContext(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ExecutionSuiteSnapshot(
                        UUID.randomUUID(), "Suite", "playwright", "1.52", "WEB",
                        null, "tests", Map.of(), Map.of()),
                new ExecutionEnvironmentSnapshot(
                        UUID.randomUUID(), "QA", "TEST", "https://example.test",
                        Map.of(), Map.of()),
                List.of(),
                Map.of(),
                new ExecutionRunnerContext(
                        UUID.randomUUID(), "runner", "1", "linux", "amd64",
                        capabilities, Map.of()),
                new ExecutionMetadata(
                        UUID.randomUUID(), now, now, Duration.ofMinutes(5),
                        ExecutionRetryPolicy.DISABLED));
    }
}
