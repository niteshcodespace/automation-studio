package com.automationstudio.api.dto.environment;

import jakarta.validation.constraints.NotNull;

public record EnvironmentDefaultRequest(
        @NotNull(message = "Environment default flag must not be null")
        Boolean isDefault) {
}
