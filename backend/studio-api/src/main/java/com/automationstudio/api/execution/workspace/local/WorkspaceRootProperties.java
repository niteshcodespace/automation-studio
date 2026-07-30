package com.automationstudio.api.execution.workspace.local;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "automation.runner.workspace")
public record WorkspaceRootProperties(String root) {
}
