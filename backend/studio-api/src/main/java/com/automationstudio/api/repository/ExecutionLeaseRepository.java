package com.automationstudio.api.repository;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.ExecutionLease;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionLeaseRepository extends JpaRepository<ExecutionLease, UUID> {

    Optional<ExecutionLease> findByClaimToken(UUID claimToken);

    List<ExecutionLease> findByRunnerId(String runnerId);

    @Query("""
            SELECT COUNT(lease)
            FROM ExecutionLease lease
            JOIN lease.execution execution
            WHERE lease.runnerId = :runnerId
              AND lease.leaseExpiresAt > :evaluatedAt
              AND execution.status IN :statuses
            """)
    long countCapacityConsumingLeases(
            @Param("runnerId") String runnerId,
            @Param("evaluatedAt") OffsetDateTime evaluatedAt,
            @Param("statuses") Collection<ExecutionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT lease
            FROM ExecutionLease lease
            WHERE lease.executionId = :executionId
            """)
    Optional<ExecutionLease> findByExecutionIdForUpdate(
            @Param("executionId") UUID executionId);
}
