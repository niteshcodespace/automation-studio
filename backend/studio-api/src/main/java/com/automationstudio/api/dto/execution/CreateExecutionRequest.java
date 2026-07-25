package com.automationstudio.api.dto.execution;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record CreateExecutionRequest(
        @NotNull UUID environmentId,
        @NotNull UUID automationSuiteId,
        @NotNull ExecutionSelectionMode selectionMode,
        List<UUID> testCaseIds) {
}
