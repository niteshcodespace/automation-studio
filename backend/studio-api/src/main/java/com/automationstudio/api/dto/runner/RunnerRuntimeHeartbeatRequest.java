package com.automationstudio.api.dto.runner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RunnerRuntimeHeartbeatRequest(
        @NotBlank
        @Size(max = 150)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,149}$")
        String runnerKey) {
}
