package com.automationstudio.api.service.impl;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.AutomationTestCaseRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.service.AutomationTestCaseService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AutomationTestCaseServiceImpl implements AutomationTestCaseService {

    private final AutomationTestCaseRepository testCaseRepository;
    private final AutomationSuiteRepository automationSuiteRepository;
    private final ProjectRepository projectRepository;

    public AutomationTestCaseServiceImpl(
            AutomationTestCaseRepository testCaseRepository,
            AutomationSuiteRepository automationSuiteRepository,
            ProjectRepository projectRepository) {
        this.testCaseRepository = testCaseRepository;
        this.automationSuiteRepository = automationSuiteRepository;
        this.projectRepository = projectRepository;
    }

    @Override
    public AutomationTestCase create(
            UUID projectId, UUID suiteId, AutomationTestCase testCase) {
        verifyProject(projectId);
        AutomationSuite suite = lockSuite(projectId, suiteId);
        String name = normalizeRequired(testCase.getName(), "Test case name");
        String caseReference = normalizeRequired(
                testCase.getCaseReference(), "Test case reference");
        rejectDuplicateName(suiteId, name, null);
        rejectDuplicateReference(suiteId, caseReference, null);
        int position = nextPosition(suiteId);

        testCase.setId(null);
        testCase.setAutomationSuite(suite);
        testCase.setName(name);
        testCase.setCaseReference(caseReference);
        testCase.setPosition(position);
        testCase.setVersion(0);
        testCase.setCreatedAt(null);
        testCase.setUpdatedAt(null);
        if (testCase.getStatus() == null) {
            testCase.setStatus(AutomationTestCaseStatus.ACTIVE);
        }
        return testCaseRepository.save(testCase);
    }

    @Override
    @Transactional(readOnly = true)
    public AutomationTestCase get(UUID projectId, UUID suiteId, UUID caseId) {
        verifyProject(projectId);
        findSuite(projectId, suiteId);
        return findCase(suiteId, caseId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AutomationTestCase> list(
            UUID projectId,
            UUID suiteId,
            AutomationTestCaseStatus status,
            Pageable pageable) {
        verifyProject(projectId);
        findSuite(projectId, suiteId);
        if (status == null) {
            return testCaseRepository.findByAutomationSuiteId(suiteId, pageable);
        }
        return testCaseRepository.findByAutomationSuiteIdAndStatus(suiteId, status, pageable);
    }

    @Override
    public AutomationTestCase update(
            UUID projectId, UUID suiteId, UUID caseId, AutomationTestCase updates) {
        verifyProject(projectId);
        lockSuite(projectId, suiteId);
        AutomationTestCase existing = lockCase(suiteId, caseId);
        String name = normalizeRequired(updates.getName(), "Test case name");
        String caseReference = normalizeRequired(
                updates.getCaseReference(), "Test case reference");
        rejectDuplicateName(suiteId, name, caseId);
        rejectDuplicateReference(suiteId, caseReference, caseId);

        existing.setName(name);
        existing.setDescription(updates.getDescription());
        existing.setCaseReference(caseReference);
        existing.setConfiguration(updates.getConfiguration());
        return testCaseRepository.save(existing);
    }

    @Override
    public AutomationTestCase updateStatus(
            UUID projectId,
            UUID suiteId,
            UUID caseId,
            AutomationTestCaseStatus status) {
        verifyProject(projectId);
        lockSuite(projectId, suiteId);
        AutomationTestCase testCase = lockCase(suiteId, caseId);
        if (status == null) {
            throw new InvalidRequestException("Automation test case status must not be null");
        }
        testCase.setStatus(status);
        return testCaseRepository.save(testCase);
    }

    @Override
    public void delete(UUID projectId, UUID suiteId, UUID caseId) {
        verifyProject(projectId);
        lockSuite(projectId, suiteId);
        AutomationTestCase testCase = lockCase(suiteId, caseId);
        testCaseRepository.delete(testCase);
        testCaseRepository.flush();
    }

    @Override
    public List<AutomationTestCase> reorder(
            UUID projectId, UUID suiteId, List<UUID> orderedCaseIds) {
        verifyProject(projectId);
        lockSuite(projectId, suiteId);
        List<AutomationTestCase> current = testCaseRepository
                .findAllByAutomationSuiteIdForUpdate(suiteId);
        validateCompleteMembership(current, orderedCaseIds);
        if (current.isEmpty()) {
            return List.of();
        }

        Map<UUID, AutomationTestCase> byId = new HashMap<>();
        current.forEach(testCase -> byId.put(testCase.getId(), testCase));
        List<AutomationTestCase> reordered = java.util.stream.IntStream
                .range(0, orderedCaseIds.size())
                .mapToObj(position -> {
                    AutomationTestCase testCase = byId.get(orderedCaseIds.get(position));
                    testCase.setPosition(position);
                    return testCase;
                })
                .toList();
        testCaseRepository.saveAllAndFlush(reordered);
        return testCaseRepository.findByAutomationSuiteIdOrderByPositionAscIdAsc(suiteId);
    }

    private void verifyProject(UUID projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found with id: " + projectId);
        }
    }

    private AutomationSuite findSuite(UUID projectId, UUID suiteId) {
        return automationSuiteRepository.findByProjectIdAndId(projectId, suiteId)
                .orElseThrow(() -> missingSuite(projectId, suiteId));
    }

    private AutomationSuite lockSuite(UUID projectId, UUID suiteId) {
        return automationSuiteRepository.findByProjectIdAndIdForUpdate(projectId, suiteId)
                .orElseThrow(() -> missingSuite(projectId, suiteId));
    }

    private AutomationTestCase findCase(UUID suiteId, UUID caseId) {
        return testCaseRepository.findByAutomationSuiteIdAndId(suiteId, caseId)
                .orElseThrow(() -> missingCase(suiteId, caseId));
    }

    private AutomationTestCase lockCase(UUID suiteId, UUID caseId) {
        return testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(suiteId, caseId)
                .orElseThrow(() -> missingCase(suiteId, caseId));
    }

    private ResourceNotFoundException missingSuite(UUID projectId, UUID suiteId) {
        return new ResourceNotFoundException(
                "Automation suite not found with id: " + suiteId + " in project: " + projectId);
    }

    private ResourceNotFoundException missingCase(UUID suiteId, UUID caseId) {
        return new ResourceNotFoundException(
                "Automation test case not found with id: " + caseId + " in suite: " + suiteId);
    }

    private String normalizeRequired(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidRequestException(label + " must not be blank");
        }
        return value.trim();
    }

    private void rejectDuplicateName(UUID suiteId, String name, UUID excludedId) {
        boolean duplicate = excludedId == null
                ? testCaseRepository.existsByAutomationSuiteIdAndName(suiteId, name)
                : testCaseRepository.existsByAutomationSuiteIdAndNameAndIdNot(
                        suiteId, name, excludedId);
        if (duplicate) {
            throw new DuplicateResourceException(
                    "Automation test case with name '" + name
                            + "' already exists in suite: " + suiteId);
        }
    }

    private void rejectDuplicateReference(
            UUID suiteId, String caseReference, UUID excludedId) {
        boolean duplicate = excludedId == null
                ? testCaseRepository.existsByAutomationSuiteIdAndCaseReference(
                        suiteId, caseReference)
                : testCaseRepository.existsByAutomationSuiteIdAndCaseReferenceAndIdNot(
                        suiteId, caseReference, excludedId);
        if (duplicate) {
            throw new DuplicateResourceException(
                    "Automation test case with reference '" + caseReference
                            + "' already exists in suite: " + suiteId);
        }
    }

    private int nextPosition(UUID suiteId) {
        return testCaseRepository.findMaximumPositionByAutomationSuiteId(suiteId)
                .map(maximum -> {
                    if (maximum == Integer.MAX_VALUE) {
                        throw new ResourceConflictException(
                                "No additional test-case position is available in suite: "
                                        + suiteId);
                    }
                    return maximum + 1;
                })
                .orElse(0);
    }

    private void validateCompleteMembership(
            List<AutomationTestCase> current, List<UUID> orderedCaseIds) {
        if (orderedCaseIds == null) {
            throw new InvalidRequestException("caseIds must not be null");
        }
        if (orderedCaseIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new InvalidRequestException("caseIds must not contain null values");
        }
        if (new HashSet<>(orderedCaseIds).size() != orderedCaseIds.size()) {
            throw new InvalidRequestException("caseIds must not contain duplicates");
        }
        var currentIds = current.stream().map(AutomationTestCase::getId).collect(
                java.util.stream.Collectors.toSet());
        if (orderedCaseIds.size() != current.size()
                || !currentIds.equals(new HashSet<>(orderedCaseIds))) {
            throw new InvalidRequestException(
                    "caseIds must contain the complete current suite membership exactly once");
        }
    }
}
