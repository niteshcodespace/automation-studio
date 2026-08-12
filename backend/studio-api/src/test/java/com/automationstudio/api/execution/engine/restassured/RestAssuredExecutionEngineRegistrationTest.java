package com.automationstudio.api.execution.engine.restassured;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.config.TimeConfiguration;
import com.automationstudio.api.execution.engine.ExecutionEngineNotFoundException;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistryImpl;
import com.automationstudio.engine.restassured.RestAssuredEnginePlugin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RestAssuredExecutionEngineRegistrationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RestAssuredExecutionEngineConfiguration.class,
                    TimeConfiguration.class, ExecutionEngineRegistryImpl.class);

    @Test
    void staticallyRegistersExactIdentityOnceWhenRunnerWorkspaceIsEnabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RestAssuredEnginePlugin.class);
            assertThatThrownBy(() -> context.getBean(ExecutionEngineRegistry.class)
                    .resolve(RestAssuredEnginePlugin.ENGINE_ID,
                            RestAssuredEnginePlugin.IMPLEMENTATION_VERSION))
                    .isInstanceOf(ExecutionEngineNotFoundException.class);
        });
        runner.withPropertyValues("automation.runner.workspace.root=C:/safe-workspace")
                .run(context -> {
                    assertThat(context).hasSingleBean(RestAssuredEnginePlugin.class);
                    ExecutionEngineRegistry registry = context.getBean(ExecutionEngineRegistry.class);
                    assertThat(registry.resolve(RestAssuredEnginePlugin.ENGINE_ID,
                            RestAssuredEnginePlugin.IMPLEMENTATION_VERSION).engine())
                            .isSameAs(context.getBean(RestAssuredEnginePlugin.class));
                    assertThat(registry.supportedEngines()).singleElement()
                            .satisfies(descriptor -> {
                                assertThat(descriptor.engineId()).isEqualTo("rest-assured");
                                assertThat(descriptor.implementationVersion()).isEqualTo("6.0.1");
                            });
                });
    }
}
