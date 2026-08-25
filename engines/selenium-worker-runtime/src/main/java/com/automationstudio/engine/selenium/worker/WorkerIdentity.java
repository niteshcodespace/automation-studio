package com.automationstudio.engine.selenium.worker;

import java.time.Instant;
import java.util.UUID;

record WorkerIdentity(UUID executionId, String logicalId, long pid, Instant processStart) {
    WorkerIdentity {
        if (executionId == null || logicalId == null || !logicalId.matches("selenium-worker-[a-f0-9]{32}")
                || pid < 1 || processStart == null) throw new IllegalArgumentException("Invalid worker identity");
    }
    static WorkerIdentity current(UUID executionId) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        return new WorkerIdentity(executionId, "selenium-worker-" + executionId.toString().replace("-", ""),
                ProcessHandle.current().pid(), info.startInstant().orElse(Instant.EPOCH));
    }
}
