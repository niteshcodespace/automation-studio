package com.automationstudio.api.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ExecutionEngineRegistryTest {

    @Test
    void resolvesExactVersionsAndListsDescriptorsDeterministically() {
        TestEngine second = engine("selenium", "4");
        TestEngine first = engine("playwright", "2");
        ExecutionEngineRegistry registry =
                new ExecutionEngineRegistryImpl(List.of(second, first));

        assertThat(registry.resolve("playwright").engine()).isSameAs(first);
        assertThat(registry.resolve("selenium", "4").engine()).isSameAs(second);
        assertThat(registry.supportedEngines())
                .extracting(ExecutionEngineDescriptor::engineName)
                .containsExactly("playwright", "selenium");
        assertThatThrownBy(() -> registry.supportedEngines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateNullAndUnknownRegistrations() {
        assertThatThrownBy(() -> new ExecutionEngineRegistryImpl(
                List.of(engine("playwright", "1"), engine("playwright", "1"))))
                .isInstanceOf(ExecutionEngineRegistrationException.class)
                .hasMessage("Duplicate execution engine registration")
                .hasMessageNotContaining("playwright");
        List<ExecutionEngine> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new ExecutionEngineRegistryImpl(withNull))
                .isInstanceOf(ExecutionEngineRegistrationException.class);
        ExecutionEngine nullDescriptor = new ExecutionEngine() {
            @Override
            public ExecutionEngineDescriptor descriptor() {
                return null;
            }

            @Override
            public void validate(
                    com.automationstudio.api.execution.ExecutionContext context) {
            }
        };
        assertThatThrownBy(() -> new ExecutionEngineRegistryImpl(List.of(nullDescriptor)))
                .isInstanceOf(ExecutionEngineRegistrationException.class);

        ExecutionEngineRegistry registry =
                new ExecutionEngineRegistryImpl(List.of(engine("playwright", "1")));
        assertThatThrownBy(() -> registry.resolve("unknown", "1"))
                .isInstanceOf(ExecutionEngineNotFoundException.class);
        assertThatThrownBy(() -> registry.resolve("playwright", "2"))
                .isInstanceOf(ExecutionEngineCompatibilityException.class);
    }

    @Test
    void supportsEmptyRegistryAndDefensivelyCopiesInput() {
        List<ExecutionEngine> engines = new ArrayList<>();
        ExecutionEngineRegistry registry = new ExecutionEngineRegistryImpl(engines);
        engines.add(engine("late", "1"));

        assertThat(registry.supportedEngines()).isEmpty();
        assertThatThrownBy(() -> registry.resolve("late", "1"))
                .isInstanceOf(ExecutionEngineNotFoundException.class)
                .hasMessage("Execution engine was not found")
                .hasMessageNotContaining("late");
    }

    @Test
    void requiresVersionWhenAnEngineHasMultipleRegisteredVersions() {
        ExecutionEngineRegistry registry = new ExecutionEngineRegistryImpl(
                List.of(engine("playwright", "1"), engine("playwright", "2")));

        assertThatThrownBy(() -> registry.resolve("playwright"))
                .isInstanceOf(ExecutionEngineCompatibilityException.class);
    }

    @Test
    void safelyServesConcurrentLookups() throws Exception {
        ExecutionEngineRegistry registry =
                new ExecutionEngineRegistryImpl(List.of(engine("playwright", "1")));
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Callable<ExecutionEngineSupport>> calls =
                    java.util.stream.IntStream.range(0, 100)
                            .mapToObj(ignored -> (java.util.concurrent.Callable<ExecutionEngineSupport>)
                                    () -> registry.resolve("playwright", "1"))
                            .toList();
            assertThat(executor.invokeAll(calls))
                    .allSatisfy(future -> assertThat(future.get().descriptor().engineName())
                            .isEqualTo("playwright"));
        }
    }

    static TestEngine engine(String name, String version) {
        return new TestEngine(new ExecutionEngineDescriptor(
                name, version, name, Set.of(), Set.of()));
    }

    record TestEngine(ExecutionEngineDescriptor descriptor) implements ExecutionEngine {
        @Override
        public void validate(com.automationstudio.api.execution.ExecutionContext context) {
        }
    }
}
