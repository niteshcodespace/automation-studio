package com.automationstudio.api.execution.engine.playwright.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionExecutionContext;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionExecutor;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightActionOutcome;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightScenarioExecutionOutcome;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifest;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PlaywrightRuntimeBoundaryTest {

    @Test
    void exposedRuntimeAndDomainContractsContainNoPlaywrightSdkTypes() {
        List<Class<?>> contracts = List.of(
                PlaywrightRuntime.class,
                PlaywrightActionRuntime.class,
                PlaywrightRuntimeSession.class,
                PlaywrightRuntimeMetrics.class,
                PlaywrightRuntimeResult.class,
                PlaywrightRuntimeException.class,
                PlaywrightActionExecutor.class,
                PlaywrightActionExecutionContext.class,
                PlaywrightActionOutcome.class,
                PlaywrightScenarioExecutionOutcome.class,
                ExecutionEngineDescriptor.class,
                PlaywrightScenarioManifest.class);

        for (Class<?> contract : contracts) {
            exposedTypes(contract).forEach(type ->
                    assertThat(type.getName()).doesNotStartWith("com.microsoft.playwright"));
        }
    }

    @Test
    void sdkSeamAndConcreteHandlesAreNotPublic() {
        assertThat(Modifier.isPublic(PlaywrightSdk.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(ChromiumPlaywrightSdk.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(DefaultPlaywrightRuntimeSession.class.getModifiers()))
                .isFalse();
        assertThat(Modifier.isPublic(MonotonicTicker.class.getModifiers())).isFalse();
    }

    private Stream<Class<?>> exposedTypes(Class<?> type) {
        Stream<Class<?>> methodTypes = Stream.of(type.getMethods())
                .flatMap(method -> Stream.concat(
                        Stream.of(method.getReturnType()),
                        Stream.of(method.getParameterTypes())));
        Stream<Class<?>> constructorTypes = Stream.of(type.getConstructors())
                .flatMap(constructor -> Stream.of(constructor.getParameterTypes()));
        Stream<Class<?>> componentTypes = type.isRecord()
                ? Stream.of(type.getRecordComponents()).map(RecordComponent::getType)
                : Stream.empty();
        return Stream.of(methodTypes, constructorTypes, componentTypes).flatMap(stream -> stream);
    }
}
