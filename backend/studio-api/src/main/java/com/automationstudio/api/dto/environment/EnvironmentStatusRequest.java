package com.automationstudio.api.dto.environment;

import com.automationstudio.api.domain.EnvironmentStatus;
import jakarta.validation.constraints.NotNull;

public record EnvironmentStatusRequest(
        @NotNull(message = "Environment status must not be null")
        EnvironmentStatus status) {
}
