package com.automationstudio.api.execution.engine.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineCompatibilityException;
import com.automationstudio.api.execution.engine.ExecutionEngineNotFoundException;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistryImpl;
import com.automationstudio.api.execution.engine.playwright.action.PlaywrightOrderedScenarioRunner;
import com.automationstudio.api.execution.engine.playwright.action.SelectorResolver;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightConfigurationParser;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifestLoader;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightRuntime;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessResolver;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PlaywrightExecutionEngineRegistrationTest {

    private static final String WORKSPACE_PROPERTY =
            "automation.runner.workspace.root=C:/automation-studio-test-workspaces";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    PlaywrightExecutionEngine.class,
                    RegistrationTestConfiguration.class);

    @Test
    void registersOnlyWhenWorkspaceSupportIsEnabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(PlaywrightExecutionEngine.class);
            assertThat(context).hasSingleBean(EngineWorkspaceAccessResolver.class);
            ExecutionEngineRegistry registry = context.getBean(ExecutionEngineRegistry.class);
            assertThatThrownBy(() -> registry.resolve(
                    PlaywrightEngineDescriptor.ENGINE_NAME,
                    PlaywrightEngineDescriptor.ENGINE_VERSION))
                    .isInstanceOf(ExecutionEngineNotFoundException.class);
            verifyStartupSafety(context);
        });

        contextRunner.withPropertyValues(WORKSPACE_PROPERTY).run(context -> {
            assertThat(context).hasSingleBean(EngineWorkspaceAccessResolver.class);
            assertThat(context).hasSingleBean(PlaywrightExecutionEngine.class);
            assertThat(context.getBeansOfType(ExecutionEngine.class).values())
                    .singleElement()
                    .isInstanceOf(PlaywrightExecutionEngine.class);

            ExecutionEngineRegistry registry = context.getBean(ExecutionEngineRegistry.class);
            assertThat(registry.resolve(
                            PlaywrightEngineDescriptor.ENGINE_NAME,
                            PlaywrightEngineDescriptor.ENGINE_VERSION)
                    .engine())
                    .isSameAs(context.getBean(PlaywrightExecutionEngine.class));
            assertThat(registry.supportedEngines())
                    .containsExactly(PlaywrightEngineDescriptor.descriptor());
            assertThatThrownBy(() -> registry.resolve(
                    "Playwright-java", PlaywrightEngineDescriptor.ENGINE_VERSION))
                    .isInstanceOf(ExecutionEngineNotFoundException.class);
            assertThatThrownBy(() -> registry.resolve(
                    PlaywrightEngineDescriptor.ENGINE_NAME, "1.61.1"))
                    .isInstanceOf(ExecutionEngineCompatibilityException.class);
            assertThatThrownBy(() -> registry.resolve(
                    PlaywrightEngineDescriptor.ENGINE_NAME.toUpperCase(),
                    PlaywrightEngineDescriptor.ENGINE_VERSION))
                    .isInstanceOf(ExecutionEngineNotFoundException.class);
            verifyStartupSafety(context);
        });
    }

    private void verifyStartupSafety(
            org.springframework.context.ApplicationContext context) {
        verifyNoInteractions(
                context.getBean(PlaywrightConfigurationParser.class),
                context.getBean(EngineWorkspaceAccessResolver.class),
                context.getBean(PlaywrightScenarioManifestLoader.class),
                context.getBean(PlaywrightRuntime.class),
                context.getBean(PlaywrightOrderedScenarioRunner.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class RegistrationTestConfiguration {

        @Bean
        PlaywrightConfigurationParser configurationParser() {
            return mock(PlaywrightConfigurationParser.class);
        }

        @Bean
        EngineWorkspaceAccessResolver workspaceAccessResolver() {
            return mock(EngineWorkspaceAccessResolver.class);
        }

        @Bean
        PlaywrightScenarioManifestLoader manifestLoader() {
            return mock(PlaywrightScenarioManifestLoader.class);
        }

        @Bean
        PlaywrightRuntime runtime() {
            return mock(PlaywrightRuntime.class);
        }

        @Bean
        PlaywrightOrderedScenarioRunner scenarioRunner() {
            return mock(PlaywrightOrderedScenarioRunner.class);
        }

        @Bean
        SelectorResolver selectorResolver() {
            return mock(SelectorResolver.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ExecutionEngineRegistry executionEngineRegistry(List<ExecutionEngine> engines) {
            return new ExecutionEngineRegistryImpl(engines);
        }
    }
}
