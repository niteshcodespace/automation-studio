package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class StructuralTrustAuthorityTest {

    @Test
    void positiveObservationAndProbeTypesAreNotPublicApi() {
        assertThat(Modifier.isPublic(ExecutionCancellationObservation.class.getModifiers()))
                .isFalse();
        assertThat(Modifier.isPublic(ExecutionCancellationProbe.class.getModifiers()))
                .isFalse();
        assertThat(Modifier.isPublic(RepositoryExecutionCancellationProbe.class.getModifiers()))
                .isFalse();
    }

    @Test
    void probeInjectingOrchestratorConstructorIsNotPublic() {
        Constructor<?> injectingConstructor = Arrays.stream(
                        ExecutionOrchestratorImpl.class.getDeclaredConstructors())
                .filter(constructor -> Arrays.asList(constructor.getParameterTypes())
                        .contains(ExecutionCancellationProbe.class))
                .findFirst()
                .orElseThrow();

        assertThat(Modifier.isPublic(injectingConstructor.getModifiers())).isFalse();
    }

    @Test
    void trustedCoordinatorConstructorIsNotPublic() {
        Constructor<?> trustedConstructor = Arrays.stream(
                        RunnerPipelineCoordinatorImpl.class.getDeclaredConstructors())
                .filter(constructor -> Arrays.asList(constructor.getParameterTypes())
                        .contains(ExecutionOrchestratorImpl.class))
                .findFirst()
                .orElseThrow();

        assertThat(Modifier.isPublic(trustedConstructor.getModifiers())).isFalse();
    }

    @Test
    void springAssemblyOwnsBothTrustedBeanFactoriesInsidePackageBoundary() {
        assertThat(PlatformExecutionConfiguration.class.isAnnotationPresent(Configuration.class))
                .isTrue();
        Method orchestratorFactory = beanMethodReturning(ExecutionOrchestratorImpl.class);
        Method coordinatorFactory = beanMethodReturning(RunnerPipelineCoordinator.class);

        assertThat(Modifier.isPublic(orchestratorFactory.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(coordinatorFactory.getModifiers())).isFalse();
    }

    private static Method beanMethodReturning(Class<?> returnType) {
        return Arrays.stream(PlatformExecutionConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == returnType)
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .findFirst()
                .orElseThrow();
    }
}
