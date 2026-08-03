package com.automationstudio.api.execution.workspace.local;

import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceProvider;
import com.automationstudio.api.execution.preparation.SourcePreparationService;
import com.automationstudio.api.execution.preparation.SourcePreparationServiceImpl;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.secret.ExecutionSecretScopeFactory;
import com.automationstudio.api.execution.orchestration.ExecutionOrchestrator;
import com.automationstudio.api.execution.orchestration.ExecutionOrchestratorImpl;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessResolver;
import com.automationstudio.api.execution.workspace.local.access.LocalEngineWorkspaceAccessResolver;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.source.materialization.SourceMaterializer;
import com.automationstudio.api.source.materialization.git.GitMaterializationProperties;
import com.automationstudio.api.source.materialization.git.GitSourceMaterializer;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "automation.runner.workspace.root")
@EnableConfigurationProperties({
        WorkspaceRootProperties.class,
        GitMaterializationProperties.class
})
public class LocalWorkspaceConfiguration {

    @Bean
    LocalWorkspaceProvider localWorkspaceProvider(
            WorkspaceRootProperties properties,
            Clock clock) {
        return new LocalWorkspaceProvider(properties, clock);
    }

    @Bean
    WorkspaceManager workspaceManager(WorkspaceProvider provider) {
        return new WorkspaceManager(provider);
    }

    @Bean
    EngineWorkspaceAccessResolver engineWorkspaceAccessResolver(
            LocalWorkspaceProvider provider) {
        return new LocalEngineWorkspaceAccessResolver(provider);
    }

    @Bean
    SourceMaterializer sourceMaterializer(
            LocalWorkspaceProvider provider,
            GitMaterializationProperties properties,
            Clock clock) {
        return new GitSourceMaterializer(
                provider, new SourceConfigurationValidator(), properties, clock);
    }

    @Bean
    SourcePreparationService sourcePreparationService(
            WorkspaceManager workspaceManager,
            SourceMaterializer sourceMaterializer,
            Clock clock) {
        return new SourcePreparationServiceImpl(
                workspaceManager, sourceMaterializer, clock);
    }

    @Bean
    ExecutionOrchestrator executionOrchestrator(
            SourcePreparationService sourcePreparationService,
            ExecutionEngineRegistry engineRegistry,
            WorkspaceManager workspaceManager,
            ExecutionSecretScopeFactory secretScopeFactory,
            Clock clock) {
        return new ExecutionOrchestratorImpl(
                sourcePreparationService,
                engineRegistry,
                workspaceManager,
                secretScopeFactory,
                clock);
    }
}
