package com.automationstudio.api.repository;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.entity.AutomationTestCase;
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

public interface AutomationTestCaseRepository extends JpaRepository<AutomationTestCase, UUID> {

    Optional<AutomationTestCase> findByAutomationSuiteIdAndId(UUID automationSuiteId, UUID id);

    boolean existsByAutomationSuiteId(UUID automationSuiteId);

    Page<AutomationTestCase> findByAutomationSuiteId(UUID automationSuiteId, Pageable pageable);

    Page<AutomationTestCase> findByAutomationSuiteIdAndStatus(
            UUID automationSuiteId, AutomationTestCaseStatus status, Pageable pageable);

    boolean existsByAutomationSuiteIdAndName(UUID automationSuiteId, String name);

    boolean existsByAutomationSuiteIdAndCaseReference(
            UUID automationSuiteId, String caseReference);

    boolean existsByAutomationSuiteIdAndNameAndIdNot(
            UUID automationSuiteId, String name, UUID id);

    boolean existsByAutomationSuiteIdAndCaseReferenceAndIdNot(
            UUID automationSuiteId, String caseReference, UUID id);

    @Query("""
            select max(testCase.position)
            from AutomationTestCase testCase
            where testCase.automationSuite.id = :automationSuiteId
            """)
    Optional<Integer> findMaximumPositionByAutomationSuiteId(
            @Param("automationSuiteId") UUID automationSuiteId);

    List<AutomationTestCase> findByAutomationSuiteIdOrderByPositionAscIdAsc(
            UUID automationSuiteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select testCase
            from AutomationTestCase testCase
            where testCase.automationSuite.id = :automationSuiteId
              and testCase.id = :caseId
            """)
    Optional<AutomationTestCase> findByAutomationSuiteIdAndIdForUpdate(
            @Param("automationSuiteId") UUID automationSuiteId,
            @Param("caseId") UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select testCase
            from AutomationTestCase testCase
            where testCase.automationSuite.id = :automationSuiteId
            order by testCase.position, testCase.id
            """)
    List<AutomationTestCase> findAllByAutomationSuiteIdForUpdate(
            @Param("automationSuiteId") UUID automationSuiteId);
}
