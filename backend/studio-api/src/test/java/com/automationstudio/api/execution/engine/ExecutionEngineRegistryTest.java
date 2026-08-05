package com.automationstudio.api.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
                .extracting(ExecutionEngineDescriptor::engineId)
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
                .isInstanceOf(ExecutionEngineInvalidDescriptorException.class)
                .hasMessage("Execution engine registration is invalid");
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
                .isInstanceOf(ExecutionEngineInvalidDescriptorException.class)
                .hasMessage("Execution engine descriptor is invalid");

        ExecutionEngineRegistry registry =
                new ExecutionEngineRegistryImpl(List.of(engine("playwright", "1")));
        assertThatThrownBy(() -> registry.resolve("unknown", "1"))
                .isInstanceOf(ExecutionEngineNotFoundException.class)
                .hasMessage("Execution engine was not found");
        assertThatThrownBy(() -> registry.resolve("playwright", "2"))
                .isInstanceOf(ExecutionEngineVersionNotSupportedException.class)
                .hasMessage("Execution engine implementation version is not supported")
                .hasMessageNotContaining("playwright")
                .hasMessageNotContaining("2");
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
                .isInstanceOf(ExecutionEngineAmbiguousException.class)
                .hasMessage("Execution engine registration is ambiguous")
                .hasMessageNotContaining("playwright");
    }

    @Test
    void treatsEngineIdentityAndImplementationVersionAsExactCaseSensitiveValues() {
        ExecutionEngine lowerCase = engine("playwright", "Release-1");
        ExecutionEngine upperCase = engine("PLAYWRIGHT", "release-1");
        ExecutionEngineRegistry registry =
                new ExecutionEngineRegistryImpl(List.of(upperCase, lowerCase));

        assertThat(registry.resolve("playwright", "Release-1").engine())
                .isSameAs(lowerCase);
        assertThat(registry.resolve("PLAYWRIGHT", "release-1").engine())
                .isSameAs(upperCase);
        assertThatThrownBy(() -> registry.resolve("playwright", "release-1"))
                .isInstanceOf(ExecutionEngineVersionNotSupportedException.class);
        assertThatThrownBy(() -> registry.resolve("Playwright", "Release-1"))
                .isInstanceOf(ExecutionEngineNotFoundException.class);
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
                    .allSatisfy(future -> assertThat(future.get().descriptor().engineId())
                            .isEqualTo("playwright"));
        }
    }

    @Test
    void canonicalizesRegistrationOrderAcrossEquivalentInputs() {
        List<ExecutionEngineDescriptor> expected = List.of(
                engine("alpha", "1").descriptor(),
                engine("alpha", "2").descriptor(),
                engine("zeta", "1").descriptor());
        ExecutionEngineRegistry forward = new ExecutionEngineRegistryImpl(List.of(
                engine("zeta", "1"), engine("alpha", "2"), engine("alpha", "1")));
        ExecutionEngineRegistry reverse = new ExecutionEngineRegistryImpl(List.of(
                engine("alpha", "1"), engine("alpha", "2"), engine("zeta", "1")));

        assertThat(forward.supportedEngines()).containsExactlyElementsOf(expected);
        assertThat(reverse.supportedEngines()).containsExactlyElementsOf(expected);
    }

    @Test
    void rejectsDescriptorsThatAreInvalidOrChangeAfterRegistration() {
        ExecutionEngine throwingDescriptor = new ExecutionEngine() {
            @Override
            public ExecutionEngineDescriptor descriptor() {
                throw new IllegalStateException("internal implementation detail");
            }

            @Override
            public void validate(
                    com.automationstudio.api.execution.ExecutionContext context) {
            }
        };
        assertThatThrownBy(() -> new ExecutionEngineRegistryImpl(List.of(throwingDescriptor)))
                .isInstanceOf(ExecutionEngineInvalidDescriptorException.class)
                .hasMessage("Execution engine descriptor is invalid")
                .hasMessageNotContaining("internal implementation detail");

        AtomicInteger calls = new AtomicInteger();
        ExecutionEngine changingAtRegistration = changingEngine(calls, 1);
        assertThatThrownBy(() ->
                new ExecutionEngineRegistryImpl(List.of(changingAtRegistration)))
                .isInstanceOf(ExecutionEngineInvalidDescriptorException.class)
                .hasMessage("Execution engine descriptor is inconsistent");

        AtomicInteger lookupCalls = new AtomicInteger();
        ExecutionEngine changingAfterRegistration = changingEngine(lookupCalls, 2);
        ExecutionEngineRegistry registry =
                new ExecutionEngineRegistryImpl(List.of(changingAfterRegistration));
        assertThatThrownBy(() -> registry.resolve("stable", "1"))
                .isInstanceOf(ExecutionEngineInvalidDescriptorException.class)
                .hasMessage("Execution engine descriptor is inconsistent");
    }

    static TestEngine engine(String name, String version) {
        return new TestEngine(new ExecutionEngineDescriptor(
                name, version, name, Set.of(), Set.of()));
    }

    private static ExecutionEngine changingEngine(
            AtomicInteger calls, int stableCalls) {
        return new ExecutionEngine() {
            @Override
            public ExecutionEngineDescriptor descriptor() {
                int call = calls.incrementAndGet();
                return new ExecutionEngineDescriptor(
                        "stable", call <= stableCalls ? "1" : "2", "Stable", Set.of(), Set.of());
            }

            @Override
            public void validate(
                    com.automationstudio.api.execution.ExecutionContext context) {
            }
        };
    }

    record TestEngine(ExecutionEngineDescriptor descriptor) implements ExecutionEngine {
        @Override
        public void validate(com.automationstudio.api.execution.ExecutionContext context) {
        }
    }
}
