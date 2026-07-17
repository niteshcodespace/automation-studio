package com.automationstudio.api.service.impl;

import com.automationstudio.api.dto.project.CreateProjectRequest;
import com.automationstudio.api.dto.project.ProjectResponse;
import com.automationstudio.api.dto.project.UpdateProjectRequest;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.entity.Workspace;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.mapper.ProjectMapper;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.repository.WorkspaceRepository;
import com.automationstudio.api.service.ProjectService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectMapper projectMapper;

    public ProjectServiceImpl(
            ProjectRepository projectRepository,
            WorkspaceRepository workspaceRepository,
            ProjectMapper projectMapper) {
        this.projectRepository = projectRepository;
        this.workspaceRepository = workspaceRepository;
        this.projectMapper = projectMapper;
    }

    @Override
    public ProjectResponse createProject(UUID workspaceId, CreateProjectRequest request) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Workspace not found with id: " + workspaceId));
        String normalizedName = request.name().trim();

        if (projectRepository.existsByWorkspaceIdAndName(workspaceId, normalizedName)) {
            throw new DuplicateResourceException(
                    "Project with name '" + normalizedName
                            + "' already exists in workspace: " + workspaceId);
        }

        Project project = projectMapper.toEntity(request);
        project.setName(normalizedName);
        project.setWorkspace(workspace);

        Project savedProject = projectRepository.save(project);
        return projectMapper.toResponse(savedProject);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectResponse getProject(UUID workspaceId, UUID projectId) {
        return projectMapper.toResponse(findProject(workspaceId, projectId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectResponse> getProjects(UUID workspaceId) {
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new ResourceNotFoundException(
                    "Workspace not found with id: " + workspaceId);
        }

        return projectRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(projectMapper::toResponse)
                .toList();
    }

    @Override
    public ProjectResponse updateProject(
            UUID workspaceId,
            UUID projectId,
            UpdateProjectRequest request) {
        Project project = findProject(workspaceId, projectId);
        String requestedName = request.name().trim();
        String existingName = project.getName().trim();

        if (!requestedName.equals(existingName)
                && projectRepository.existsByWorkspaceIdAndName(workspaceId, requestedName)) {
            throw new DuplicateResourceException(
                    "Project with name '" + requestedName
                            + "' already exists in workspace: " + workspaceId);
        }

        projectMapper.updateEntity(request, project);
        project.setName(requestedName);
        Project savedProject = projectRepository.save(project);
        return projectMapper.toResponse(savedProject);
    }

    @Override
    public void deleteProject(UUID workspaceId, UUID projectId) {
        Project project = findProject(workspaceId, projectId);
        projectRepository.delete(project);
    }

    private Project findProject(UUID workspaceId, UUID projectId) {
        return projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found with id: " + projectId
                                + " in workspace: " + workspaceId));
    }
}
