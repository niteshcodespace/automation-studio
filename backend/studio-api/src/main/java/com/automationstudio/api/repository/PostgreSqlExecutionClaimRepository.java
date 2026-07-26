package com.automationstudio.api.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PostgreSqlExecutionClaimRepository implements ExecutionClaimRepository {

    private static final String LOCK_NEXT_PENDING_EXECUTION = """
            SELECT execution.id
            FROM execution
            WHERE execution.status = 'PENDING'
              AND NOT EXISTS (
                  SELECT 1
                  FROM execution_lease
                  WHERE execution_lease.execution_id = execution.id
              )
            ORDER BY execution.requested_at ASC, execution.id ASC
            FOR UPDATE OF execution SKIP LOCKED
            LIMIT 1
            """;

    private final EntityManager entityManager;

    public PostgreSqlExecutionClaimRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<UUID> lockNextPendingExecutionId() {
        return entityManager.createNativeQuery(LOCK_NEXT_PENDING_EXECUTION, UUID.class)
                .getResultStream()
                .findFirst();
    }

    @Override
    public OffsetDateTime currentDatabaseTime() {
        Instant databaseTime = (Instant) entityManager.createNativeQuery(
                "SELECT CURRENT_TIMESTAMP").getSingleResult();
        return databaseTime.atOffset(ZoneOffset.UTC);
    }
}
