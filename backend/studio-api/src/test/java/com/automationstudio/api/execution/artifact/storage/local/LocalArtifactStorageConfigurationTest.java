package com.automationstudio.api.execution.artifact.storage.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.execution.artifact.storage.ArtifactStorage;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageLimits;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalArtifactStorageConfigurationTest {

    @TempDir Path temporaryDirectory;

    @Test
    void configuresOnlyWithExplicitSeparateRootAndAppliesValidatedDefaults() {
        var runner = new ApplicationContextRunner()
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(LocalArtifactStorageConfiguration.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(ArtifactStorage.class));

        runner.withPropertyValues(
                "automation.runner.artifacts.root=" + temporaryDirectory.resolve("artifacts"),
                "automation.runner.workspace.root=" + temporaryDirectory.resolve("workspaces"))
                .run(context -> {
                    assertThat(context).hasSingleBean(ArtifactStorage.class);
                    ArtifactStorageLimits limits = context.getBean(ArtifactStorageLimits.class);
                    assertThat(limits.maximumBytesPerArtifact()).isEqualTo(104_857_600L);
                    assertThat(limits.maximumBytesPerExecution()).isEqualTo(1_073_741_824L);
                    assertThat(limits.maximumArtifactsPerExecution()).isEqualTo(100);
                    assertThat(limits.maximumConcurrentPublicationsPerExecution()).isEqualTo(4);
                });
    }

    @Test
    void invalidLimitsAndWorkspaceOverlapFailClosed() {
        var runner = new ApplicationContextRunner()
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(LocalArtifactStorageConfiguration.class);
        runner.withPropertyValues(
                "automation.runner.artifacts.root=" + temporaryDirectory.resolve("artifacts"),
                "automation.runner.artifacts.maximum-bytes-per-artifact=0")
                .run(context -> assertThat(context).hasFailed());

        Path workspace = temporaryDirectory.resolve("shared");
        runner.withPropertyValues(
                "automation.runner.artifacts.root=" + workspace.resolve("artifacts"),
                "automation.runner.workspace.root=" + workspace)
                .run(context -> assertThat(context).hasFailed());
    }
}
