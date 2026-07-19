package com.automationstudio.api.repository;

import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.domain.AutomationSuiteStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutomationSuiteRepository extends JpaRepository<AutomationSuite, UUID> {

    Optional<AutomationSuite> findByProjectIdAndId(UUID projectId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select suite
            from AutomationSuite suite
            where suite.project.id = :projectId
              and suite.id = :suiteId
            """)
    Optional<AutomationSuite> findByProjectIdAndIdForUpdate(
            @Param("projectId") UUID projectId, @Param("suiteId") UUID suiteId);

    List<AutomationSuite> findByProjectId(UUID projectId);

    Page<AutomationSuite> findByProjectId(UUID projectId, Pageable pageable);

    Optional<AutomationSuite> findByProjectIdAndName(UUID projectId, String name);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    long countByProjectId(UUID projectId);

    List<AutomationSuite> findByProjectIdAndStatus(
            UUID projectId, AutomationSuiteStatus status);

    Page<AutomationSuite> findByProjectIdAndStatus(
            UUID projectId, AutomationSuiteStatus status, Pageable pageable);
}
