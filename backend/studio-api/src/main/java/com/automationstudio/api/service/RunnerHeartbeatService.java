package com.automationstudio.api.service;

import com.automationstudio.api.service.command.RecordRunnerHeartbeatCommand;
import com.automationstudio.api.service.result.RunnerHealthResult;
import com.automationstudio.api.service.result.RunnerHeartbeatResult;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface RunnerHeartbeatService {

    RunnerHeartbeatResult recordHeartbeat(RecordRunnerHeartbeatCommand command);

    RunnerHealthResult evaluateHealth(UUID runnerId);

    RunnerHealthResult evaluateHealth(String runnerKey);

    RunnerHealthResult evaluateHealth(UUID runnerId, OffsetDateTime evaluatedAt);
}
