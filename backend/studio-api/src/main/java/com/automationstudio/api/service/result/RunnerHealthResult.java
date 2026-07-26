package com.automationstudio.api.service.result;

import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RunnerHealthResult(
        UUID runnerId,
        String runnerKey,
        RunnerStatus lifecycleStatus,
        RunnerHealth health,
        OffsetDateTime lastSeenAt,
        OffsetDateTime evaluatedAt) {
}
