package com.automationstudio.api.service.query;

import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;

public record RunnerQueryFilter(
        RunnerStatus status,
        RunnerHealth health,
        Boolean available,
        String capability,
        String label) {
}
