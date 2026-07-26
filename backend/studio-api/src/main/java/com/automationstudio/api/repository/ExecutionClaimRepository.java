package com.automationstudio.api.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionClaimRepository {

    Optional<UUID> lockNextPendingExecutionId();

    OffsetDateTime currentDatabaseTime();
}
