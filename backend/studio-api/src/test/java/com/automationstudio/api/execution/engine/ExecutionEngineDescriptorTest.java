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
    }
}
