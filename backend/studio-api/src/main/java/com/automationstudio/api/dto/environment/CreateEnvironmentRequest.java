package com.automationstudio.api.dto.environment;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateEnvironmentRequest(
        @NotBlank(message = "Environment name must not be blank")
        @Size(max = 100, message = "Environment name must not exceed 100 characters")
        String name,
        @Size(max = 1000, message = "Environment description must not exceed 1000 characters")
        String description,
        @NotBlank(message = "Environment base URL must not be blank")
        @Size(max = 500, message = "Environment base URL must not exceed 500 characters")
        String baseUrl,
        @NotNull(message = "Environment type must not be null")
        EnvironmentType type,
        Map<String, Object> configuration,
        Map<String, Object> secretReferences,
        EnvironmentStatus status,
        Boolean isDefault) {

    @Override
    public String toString() {
        return "CreateEnvironmentRequest[redacted]";
    }
}
