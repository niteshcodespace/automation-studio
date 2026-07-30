package com.automationstudio.api.execution.engine.playwright.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PlaywrightRuntimePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsSafeDefaultsAndOperatorOwnedSettings() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            PlaywrightRuntimeProperties properties =
                    context.getBean(PlaywrightRuntimeProperties.class);
            assertThat(properties.executablePath()).isEmpty();
            assertThat(properties.browserStartupTimeout()).isEqualTo(Duration.ofMinutes(1));
        });

        contextRunner.withPropertyValues(
                        "automation.runner.playwright.executable-path=C:/browsers/chromium.exe",
                        "automation.runner.playwright.browser-startup-timeout=PT30S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PlaywrightRuntimeProperties properties =
                            context.getBean(PlaywrightRuntimeProperties.class);
                    assertThat(properties.executablePath())
                            .isEqualTo("C:/browsers/chromium.exe");
                    assertThat(properties.browserStartupTimeout())
                            .isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void rejectsUnsafeOrUnboundedOperatorSettings() {
        assertInvalid("automation.runner.playwright.executable-path=relative/chromium");
        assertInvalid("automation.runner.playwright.browser-startup-timeout=PT0S");
        assertInvalid("automation.runner.playwright.browser-startup-timeout=PT6M");
    }

    private void assertInvalid(String property) {
        contextRunner.withPropertyValues(property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PlaywrightRuntimeProperties.class)
    static class TestConfiguration {
    }
}
