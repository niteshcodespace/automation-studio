package com.automationstudio.api.service.command;

import java.util.Map;

public record RegisterRunnerCommand(
        String runnerKey,
        String name,
        String description,
        String agentVersion,
        String hostname,
        String operatingSystem,
        String architecture,
        int maxConcurrency,
        Map<String, Object> capabilities,
        Map<String, Object> labels) {
}
