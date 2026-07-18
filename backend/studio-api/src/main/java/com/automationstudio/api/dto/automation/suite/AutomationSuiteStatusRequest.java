package com.automationstudio.api.dto.automation.suite;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import jakarta.validation.constraints.NotNull;

public record AutomationSuiteStatusRequest(
        @NotNull(message = "Automation suite status must not be null")
        AutomationSuiteStatus status) {
}
