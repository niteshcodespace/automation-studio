package com.automationstudio.api.dto.automation.testcase;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import jakarta.validation.constraints.NotNull;

public record AutomationTestCaseStatusRequest(
        @NotNull(message = "Automation test case status must not be null")
        AutomationTestCaseStatus status) {
}
