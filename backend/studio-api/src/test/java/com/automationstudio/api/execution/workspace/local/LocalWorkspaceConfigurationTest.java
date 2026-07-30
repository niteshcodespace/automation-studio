package com.automationstudio.api.execution.workspace.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceProvider;
import com.automationstudio.api.execution.preparation.SourcePreparationService;
import com.automationstudio.api.source.materialization.SourceMaterializer;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalWorkspaceConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(Clock.class, Clock::systemUTC)
                    .withUserConfiguration(LocalWorkspaceConfiguration.class);

    @Test
    void configuresProviderAndManagerOnlyWhenRootIsExplicit() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(WorkspaceProvider.class);
            assertThat(context).doesNotHaveBean(WorkspaceManager.class);
            assertThat(context).doesNotHaveBean(SourceMaterializer.class);
            assertThat(context).doesNotHaveBean(SourcePreparationService.class);
        });

        contextRunner
                .withPropertyValues(
                        "automation.runner.workspace.root=C:/automation-studio-test-workspaces")
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkspaceProvider.class);
                    assertThat(context).hasSingleBean(WorkspaceManager.class);
                    assertThat(context).hasSingleBean(SourceMaterializer.class);
                    assertThat(context).hasSingleBean(SourcePreparationService.class);
                    assertThat(context.getBean(WorkspaceProvider.class).providerId())
                            .isEqualTo(LocalWorkspaceProvider.PROVIDER_ID);
                });
    }
}
