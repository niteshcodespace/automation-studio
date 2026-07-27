package com.automationstudio.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RunnerHealthPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsAndValidThresholdsBind() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RunnerHealthProperties properties = context.getBean(RunnerHealthProperties.class);
            assertThat(properties.onlineThreshold()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.offlineThreshold()).isEqualTo(Duration.ofMinutes(5));
        });

        contextRunner
                .withPropertyValues(
                        "automation-studio.runners.health.online-threshold=PT30S",
                        "automation-studio.runners.health.offline-threshold=PT2M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RunnerHealthProperties properties =
                            context.getBean(RunnerHealthProperties.class);
                    assertThat(properties.onlineThreshold()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.offlineThreshold()).isEqualTo(Duration.ofMinutes(2));
                });
    }

    @Test
    void nonPositiveThresholdsFailBinding() {
        assertInvalid("PT0S", "PT5M");
        assertInvalid("-PT1S", "PT5M");
        assertInvalid("PT1M", "PT0S");
        assertInvalid("PT1M", "-PT1S");
    }

    @Test
    void equalOrLowerOfflineThresholdFailsBinding() {
        assertInvalid("PT1M", "PT1M");
        assertInvalid("PT2M", "PT1M");
    }

    private void assertInvalid(String online, String offline) {
        contextRunner
                .withPropertyValues(
                        "automation-studio.runners.health.online-threshold=" + online,
                        "automation-studio.runners.health.offline-threshold=" + offline)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RunnerHealthProperties.class)
    static class TestConfiguration {
    }
}
