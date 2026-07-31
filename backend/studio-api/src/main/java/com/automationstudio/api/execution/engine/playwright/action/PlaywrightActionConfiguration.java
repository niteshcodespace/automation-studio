package com.automationstudio.api.execution.engine.playwright.action;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlaywrightActionConfiguration {
    @Bean
    PlaywrightActionExecutorRegistry playwrightActionExecutorRegistry() {
        return new PlaywrightActionExecutorRegistry(List.of(
                new NavigateActionExecutor(),
                new ClickActionExecutor(),
                new FillActionExecutor(),
                new AssertVisibleActionExecutor(),
                new AssertTextActionExecutor(),
                new AssertUrlActionExecutor()));
    }

    @Bean
    SelectorResolver playwrightSelectorResolver() {
        return new CssSelectorResolver();
    }
}
