package com.automationstudio.api.repository;

import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunnerRepository extends JpaRepository<Runner, UUID> {

    Optional<Runner> findByRunnerKey(String runnerKey);

    boolean existsByRunnerKey(String runnerKey);

    Page<Runner> findByStatus(RunnerStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT runner FROM Runner runner WHERE runner.runnerKey = :runnerKey")
    Optional<Runner> findByRunnerKeyForUpdate(@Param("runnerKey") String runnerKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT runner FROM Runner runner WHERE runner.id = :runnerId")
    Optional<Runner> findByIdForUpdate(@Param("runnerId") UUID runnerId);
}
