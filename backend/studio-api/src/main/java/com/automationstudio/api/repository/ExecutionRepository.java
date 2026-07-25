package com.automationstudio.api.repository;

import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.domain.ExecutionStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT execution FROM Execution execution WHERE execution.id = :executionId")
    Optional<Execution> findByIdForUpdate(@Param("executionId") UUID executionId);

    boolean existsByProjectIdAndEnvironmentId(UUID projectId, UUID environmentId);

    Optional<Execution> findByProjectIdAndId(UUID projectId, UUID id);

    Page<Execution> findByProjectIdOrderByRequestedAtDescIdDesc(
            UUID projectId, Pageable pageable);

    Page<Execution> findByProjectIdAndStatusOrderByRequestedAtDescIdDesc(
            UUID projectId, ExecutionStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution
            from Execution execution
            where execution.project.id = :projectId
              and execution.id = :executionId
            """)
    Optional<Execution> findByProjectIdAndIdForUpdate(
            @Param("projectId") UUID projectId, @Param("executionId") UUID executionId);
}
