package com.automationstudio.api.service.command;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import java.util.List;
import java.util.UUID;

public record CreateExecutionCommand(
        UUID environmentId,
        UUID automationSuiteId,
        ExecutionSelectionMode selectionMode,
        List<UUID> testCaseIds) {
}
