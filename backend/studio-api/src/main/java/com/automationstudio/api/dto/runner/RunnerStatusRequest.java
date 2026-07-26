package com.automationstudio.api.dto.runner;

import com.automationstudio.api.domain.RunnerStatus;
import jakarta.validation.constraints.NotNull;

public record RunnerStatusRequest(@NotNull RunnerStatus status) {
}
