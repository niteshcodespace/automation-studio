package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.execution.artifact.storage.ArtifactPublisherFactory;
import com.automationstudio.api.execution.engine.ExecutionEngineRegistry;
import com.automationstudio.api.execution.secret.ExecutionSecretScopeFactory;
import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceProvider;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessResolver;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.execution.preparation.SourcePreparationService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Trusted platform assembly kept inside the orchestration authority boundary. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "automation.runner.workspace.root")
public class PlatformExecutionConfiguration {

    @Bean
    ExecutionOrchestratorImpl executionOrchestrator(
            SourcePreparationService sourcePreparationService,
            ExecutionEngineRegistry engineRegistry,
            WorkspaceManager workspaceManager,
            ExecutionSecretScopeFactory secretScopeFactory,
            EngineWorkspaceAccessResolver workspaceAccessResolver,
            ObjectProvider<ArtifactPublisherFactory> artifactPublisherFactory,
            ObjectProvider<ExecutionRepository> executionRepository,
            Clock clock) {
        return new ExecutionOrchestratorImpl(
                sourcePreparationService,
                engineRegistry,
                workspaceManager,
                secretScopeFactory,
                workspaceAccessResolver,
                artifactPublisherFactory.getIfAvailable(ArtifactPublisherFactory::unavailable),
                executionRepository.getIfAvailable() == null
                        ? ExecutionCancellationProbe.never()
                        : new RepositoryExecutionCancellationProbe(executionRepository.getObject()),
                new ExecutionSupervisorImpl(clock),
                clock);
    }

    @Bean
    @ConditionalOnBean({RunnerExecutionService.class, ExecutionOrchestratorImpl.class})
    RunnerPipelineCoordinator runnerPipelineCoordinator(
            RunnerExecutionService runnerExecutionService,
            ExecutionOrchestratorImpl executionOrchestrator,
            WorkspaceProvider workspaceProvider) {
        return new RunnerPipelineCoordinatorImpl(
                runnerExecutionService,
                executionOrchestrator,
                new AdmittedSourceSnapshotMapper(new SourceConfigurationValidator()),
                workspaceProvider.providerId());
    }
}
