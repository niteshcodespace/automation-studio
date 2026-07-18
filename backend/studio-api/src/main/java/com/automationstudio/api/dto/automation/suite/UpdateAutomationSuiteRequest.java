package com.automationstudio.api.dto.automation.suite;

import com.automationstudio.api.domain.SuiteType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record UpdateAutomationSuiteRequest(
        @NotBlank(message = "Automation suite name must not be blank")
        @Size(max = 150, message = "Automation suite name must not exceed 150 characters")
        String name,
        String description,
        @NotBlank(message = "Automation suite engine type must not be blank")
        @Size(max = 50, message = "Automation suite engine type must not exceed 50 characters")
        String engineType,
        @NotBlank(message = "Automation suite reference must not be blank")
        @Size(max = 300, message = "Automation suite reference must not exceed 300 characters")
        String suiteReference,
        @Size(max = 100, message = "Automation suite engine ID must not exceed 100 characters")
        String engineId,
        SuiteType suiteType,
        Map<String, Object> configuration) {
}
