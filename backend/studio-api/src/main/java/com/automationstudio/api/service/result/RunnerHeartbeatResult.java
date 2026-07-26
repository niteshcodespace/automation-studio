package com.automationstudio.api.service.result;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RunnerHeartbeatResult(
        UUID runnerId,
        String runnerKey,
        OffsetDateTime lastSeenAt,
        long heartbeatCount,
        long runtimeVersion) {
}
