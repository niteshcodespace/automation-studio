package com.automationstudio.api.repository;

import com.automationstudio.api.entity.RunnerRuntime;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunnerRuntimeRepository extends JpaRepository<RunnerRuntime, UUID> {

    Optional<RunnerRuntime> findByRunnerId(UUID runnerId);

    boolean existsByRunnerId(UUID runnerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT runtime FROM RunnerRuntime runtime WHERE runtime.runnerId = :runnerId")
    Optional<RunnerRuntime> findByRunnerIdForUpdate(@Param("runnerId") UUID runnerId);
}
