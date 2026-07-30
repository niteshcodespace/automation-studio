package com.automationstudio.api.source.materialization.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GitMaterializationPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsSafeDefaultsAndExplicitExecutable() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            GitMaterializationProperties properties =
                    context.getBean(GitMaterializationProperties.class);
            assertThat(properties.executable()).isEqualTo("git");
            assertThat(properties.commandTimeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.maxOutputBytes()).isEqualTo(65_536);
            assertThat(properties.allowLocalRepositories()).isFalse();
        });

        contextRunner.withPropertyValues(
                        "automation.runner.source.git.executable=C:/tools/git.exe",
                        "automation.runner.source.git.command-timeout=PT30S",
                        "automation.runner.source.git.max-output-bytes=4096")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GitMaterializationProperties properties =
                            context.getBean(GitMaterializationProperties.class);
                    assertThat(properties.executable()).isEqualTo("C:/tools/git.exe");
                    assertThat(properties.commandTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.maxOutputBytes()).isEqualTo(4096);
                });
    }

    @Test
    void rejectsUnsafeOrUnboundedConfiguration() {
        assertInvalid("automation.runner.source.git.command-timeout=PT0S");
        assertInvalid("automation.runner.source.git.command-timeout=PT11M");
        assertInvalid("automation.runner.source.git.max-output-bytes=0");
        assertInvalid("automation.runner.source.git.max-output-bytes=1048577");
        assertInvalid("automation.runner.source.git.executable= ");
    }

    private void assertInvalid(String property) {
        contextRunner.withPropertyValues(property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GitMaterializationProperties.class)
    static class TestConfiguration {
    }
}
