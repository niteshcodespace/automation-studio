package com.automationstudio.api.dto.execution;

import jakarta.validation.constraints.Size;

public record CancelExecutionRequest(
        @Size(max = 1000) String reason) {
}
