package com.automationstudio.api.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PostgreSqlExecutionReclaimRepository implements ExecutionReclaimRepository {

    private static final String LOCK_NEXT_EXPIRED_CLAIMED_EXECUTION = """
            SELECT execution.id
            FROM execution_lease lease
            JOIN execution ON execution.id = lease.execution_id
            WHERE execution.status = 'CLAIMED'
              AND lease.lease_expires_at <= CURRENT_TIMESTAMP
            ORDER BY lease.lease_expires_at ASC,
                     execution.requested_at ASC,
                     execution.id ASC
            FOR UPDATE OF lease, execution SKIP LOCKED
            LIMIT 1
            """;

    private final EntityManager entityManager;

    public PostgreSqlExecutionReclaimRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<UUID> lockNextExpiredClaimedExecutionId() {
        return entityManager.createNativeQuery(
                        LOCK_NEXT_EXPIRED_CLAIMED_EXECUTION, UUID.class)
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
