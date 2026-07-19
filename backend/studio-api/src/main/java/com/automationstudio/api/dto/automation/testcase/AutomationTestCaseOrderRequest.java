package com.automationstudio.api.dto.automation.testcase;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record AutomationTestCaseOrderRequest(
        @NotNull(message = "Automation test case IDs must not be null")
        List<@NotNull(message = "Automation test case ID must not be null") UUID> caseIds) {
}
