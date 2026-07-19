package com.automationstudio.api.service;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.entity.AutomationTestCase;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AutomationTestCaseService {

    AutomationTestCase create(UUID projectId, UUID suiteId, AutomationTestCase testCase);

    AutomationTestCase get(UUID projectId, UUID suiteId, UUID caseId);

    Page<AutomationTestCase> list(
            UUID projectId, UUID suiteId, AutomationTestCaseStatus status, Pageable pageable);

    AutomationTestCase update(
            UUID projectId, UUID suiteId, UUID caseId, AutomationTestCase updates);

    AutomationTestCase updateStatus(
            UUID projectId, UUID suiteId, UUID caseId, AutomationTestCaseStatus status);

    void delete(UUID projectId, UUID suiteId, UUID caseId);

    List<AutomationTestCase> reorder(
            UUID projectId, UUID suiteId, List<UUID> orderedCaseIds);
}
