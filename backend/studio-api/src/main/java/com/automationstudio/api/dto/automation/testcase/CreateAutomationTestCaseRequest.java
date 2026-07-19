package com.automationstudio.api.dto.automation.testcase;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateAutomationTestCaseRequest(
        @NotBlank(message = "Automation test case name must not be blank")
        @Size(max = 150, message = "Automation test case name must not exceed 150 characters")
        String name,
        String description,
        @NotBlank(message = "Automation test case reference must not be blank")
        @Size(max = 300, message = "Automation test case reference must not exceed 300 characters")
        String caseReference,
        Map<String, Object> configuration,
        AutomationTestCaseStatus status) {
}
