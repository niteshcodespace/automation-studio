package com.automationstudio.api.dto.runner;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record RegisterRunnerRequest(
        @NotBlank
        @Size(max = 150)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,149}$")
        String runnerKey,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 100) String agentVersion,
        @NotBlank @Size(max = 255) String hostname,
        @NotBlank @Size(max = 100) String operatingSystem,
        @NotBlank @Size(max = 50) String architecture,
        @Min(1) @Max(1000) int maxConcurrency,
        @NotNull Map<String, Object> capabilities,
        @NotNull Map<String, Object> labels) {
}
