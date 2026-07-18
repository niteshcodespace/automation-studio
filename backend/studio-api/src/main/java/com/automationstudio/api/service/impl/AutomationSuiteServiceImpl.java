package com.automationstudio.api.service.impl;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.service.AutomationSuiteService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AutomationSuiteServiceImpl implements AutomationSuiteService {

    private final AutomationSuiteRepository automationSuiteRepository;
    private final ProjectRepository projectRepository;

    public AutomationSuiteServiceImpl(
            AutomationSuiteRepository automationSuiteRepository,
            ProjectRepository projectRepository) {
        this.automationSuiteRepository = automationSuiteRepository;
        this.projectRepository = projectRepository;
    }

    @Override
    public AutomationSuite create(UUID projectId, AutomationSuite suite) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found with id: " + projectId));
        String normalizedName = suite.getName().trim();
        rejectDuplicateName(projectId, normalizedName);

        suite.setId(null);
        suite.setProject(project);
        suite.setName(normalizedName);
        if (suite.getStatus() == null) {
            suite.setStatus(AutomationSuiteStatus.ACTIVE);
        }
        return automationSuiteRepository.save(suite);
    }

    @Override
    @Transactional(readOnly = true)
    public AutomationSuite get(UUID projectId, UUID suiteId) {
        return findSuite(projectId, suiteId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AutomationSuite> list(
            UUID projectId, AutomationSuiteStatus status, Pageable pageable) {
        if (status == null) {
            return automationSuiteRepository.findByProjectId(projectId, pageable);
        }
        return automationSuiteRepository.findByProjectIdAndStatus(projectId, status, pageable);
    }

    @Override
    public AutomationSuite update(
            UUID projectId, UUID suiteId, AutomationSuite updates) {
        AutomationSuite suite = findSuite(projectId, suiteId);
        String normalizedName = updates.getName().trim();
        if (!normalizedName.equals(suite.getName().trim())) {
            rejectDuplicateName(projectId, normalizedName);
        }

        suite.setName(normalizedName);
        suite.setDescription(updates.getDescription());
        suite.setEngineType(updates.getEngineType());
        suite.setSuiteReference(updates.getSuiteReference());
        suite.setEngineId(updates.getEngineId());
        suite.setSuiteType(updates.getSuiteType());
        suite.setConfiguration(updates.getConfiguration());
        return automationSuiteRepository.save(suite);
    }

    @Override
    public AutomationSuite changeStatus(
            UUID projectId, UUID suiteId, AutomationSuiteStatus status) {
        Objects.requireNonNull(status, "Automation suite status must not be null");
        AutomationSuite suite = findSuite(projectId, suiteId);
        suite.setStatus(status);
        return automationSuiteRepository.save(suite);
    }

    @Override
    public void delete(UUID projectId, UUID suiteId) {
        automationSuiteRepository.delete(findSuite(projectId, suiteId));
    }

    private AutomationSuite findSuite(UUID projectId, UUID suiteId) {
        return automationSuiteRepository.findByProjectIdAndId(projectId, suiteId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Automation suite not found with id: " + suiteId
                                + " in project: " + projectId));
    }

    private void rejectDuplicateName(UUID projectId, String name) {
        if (automationSuiteRepository.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateResourceException(
                    "Automation suite with name '" + name
                            + "' already exists in project: " + projectId);
        }
    }
}
