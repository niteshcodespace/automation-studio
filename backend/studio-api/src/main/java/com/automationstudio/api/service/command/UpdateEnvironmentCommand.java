package com.automationstudio.api.service.command;

import com.automationstudio.api.domain.EnvironmentType;
import java.util.Map;

public record UpdateEnvironmentCommand(
        String name,
        String description,
        String baseUrl,
        EnvironmentType type,
        Map<String, Object> configuration,
        Map<String, Object> secretReferences) {
}
