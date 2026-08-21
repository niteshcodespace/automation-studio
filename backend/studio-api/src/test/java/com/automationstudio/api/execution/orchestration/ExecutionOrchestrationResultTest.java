package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ExecutionOrchestrationResultTest {

    @Test
    void publicApiCannotCarryPersistentCancellationVersion() {
        assertThat(Arrays.stream(ExecutionOrchestrationResult.class.getConstructors())
                        .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .noneMatch(type -> type == Long.class || type == long.class);
        assertThat(Arrays.stream(ExecutionOrchestrationResult.class.getMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers())))
                .noneMatch(method -> method.getName().equals("observedCancellationVersion"));
    }

    @Test
    void trustedOutcomeTypeIsNotPublic() {
        assertThat(Modifier.isPublic(
                PlatformExecutionOrchestrationResult.class.getModifiers())).isFalse();
    }
}
