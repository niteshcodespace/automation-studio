package com.automationstudio.api.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionEngineDescriptorTest {

    @Test
    void createsAnImmutableDescriptor() {
        Set<String> capabilities = new LinkedHashSet<>(Set.of("web"));
        ExecutionEngineDescriptor descriptor = new ExecutionEngineDescriptor(
                "engine", "1.0", "Engine", capabilities, Set.of("screenshots"));

        capabilities.add("mobile");

        assertThat(descriptor.engineId()).isEqualTo("engine");
        assertThat(descriptor.implementationVersion()).isEqualTo("1.0");
        assertThat(descriptor.supportedCapabilities()).containsExactly("web");
        assertThatThrownBy(() -> descriptor.supportedFeatures().add("video"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidDescriptorValues() {
        assertThatThrownBy(() -> new ExecutionEngineDescriptor(
                null, "1", "Engine", Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionEngineDescriptor(
                "engine", " ", "Engine", Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionEngineDescriptor(
                "engine", "1", "Engine", null, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExecutionEngineDescriptor(
                "engine", "1", " ", Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionEngineDescriptor(
                "engine", "1", "Engine", Set.of(" "), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hasDeterministicValueEqualityAndInspectionOrder() {
        ExecutionEngineDescriptor first = new ExecutionEngineDescriptor(
                "engine", "1.0", "Engine", Set.of("web", "api"), Set.of("trace", "report"));
        ExecutionEngineDescriptor second = new ExecutionEngineDescriptor(
                "engine", "1.0", "Engine", Set.of("api", "web"), Set.of("report", "trace"));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.supportedCapabilities()).containsExactly("api", "web");
        assertThat(first.supportedFeatures()).containsExactly("report", "trace");
    }

    @SuppressWarnings("deprecation")
    @Test
    void retainsReadOnlyCompatibilityAliases() {
        ExecutionEngineDescriptor descriptor = new ExecutionEngineDescriptor(
                "engine", "1.0", "Engine", Set.of(), Set.of());

        assertThat(descriptor.engineName()).isEqualTo(descriptor.engineId());
        assertThat(descriptor.engineVersion()).isEqualTo(descriptor.implementationVersion());
    }
}
