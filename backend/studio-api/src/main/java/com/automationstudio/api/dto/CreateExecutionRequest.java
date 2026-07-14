package com.automationstudio.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateExecutionRequest(
        @NotNull UUID projectId,
        @NotNull UUID environmentId,
        @NotNull UUID testSuiteId,
        @NotBlank @Size(max = 150) String requestedBy) {
}
