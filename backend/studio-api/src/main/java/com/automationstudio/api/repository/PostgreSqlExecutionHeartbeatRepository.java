package com.automationstudio.api.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Repository;

@Repository
public class PostgreSqlExecutionHeartbeatRepository implements ExecutionHeartbeatRepository {

    private final EntityManager entityManager;

    public PostgreSqlExecutionHeartbeatRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public OffsetDateTime currentDatabaseTime() {
        Instant databaseTime = (Instant) entityManager.createNativeQuery(
                "SELECT CURRENT_TIMESTAMP").getSingleResult();
        return databaseTime.atOffset(ZoneOffset.UTC);
    }
}
