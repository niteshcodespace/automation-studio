package com.automationstudio.api.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionReclaimRepository {

    Optional<UUID> lockNextExpiredClaimedExecutionId();

    OffsetDateTime currentDatabaseTime();
}
