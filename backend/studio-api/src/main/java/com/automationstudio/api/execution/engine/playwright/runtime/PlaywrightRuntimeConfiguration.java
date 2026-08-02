package com.automationstudio.api.execution.engine.playwright.runtime;

import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightRuntimeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlaywrightRuntimeProperties.class)
class PlaywrightRuntimeConfiguration {

    @Bean
    PlaywrightRuntime playwrightRuntime(PlaywrightRuntimeProperties properties) {
        return new DefaultPlaywrightRuntime(properties);
    }
}
