package com.automationstudio.api.execution.workspace.local;

import com.automationstudio.api.execution.workspace.WorkspaceManager;
import com.automationstudio.api.execution.workspace.WorkspaceProvider;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "automation.runner.workspace.root")
@EnableConfigurationProperties(WorkspaceRootProperties.class)
public class LocalWorkspaceConfiguration {

    @Bean
    WorkspaceProvider localWorkspaceProvider(
            WorkspaceRootProperties properties,
            Clock clock) {
        return new LocalWorkspaceProvider(properties, clock);
    }

    @Bean
    WorkspaceManager workspaceManager(WorkspaceProvider provider) {
        return new WorkspaceManager(provider);
    }
}
