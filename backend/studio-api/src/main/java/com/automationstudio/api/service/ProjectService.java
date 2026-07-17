package com.automationstudio.api.service;

import com.automationstudio.api.dto.project.CreateProjectRequest;
import com.automationstudio.api.dto.project.ProjectResponse;
import com.automationstudio.api.dto.project.UpdateProjectRequest;
import java.util.List;
import java.util.UUID;

public interface ProjectService {

    ProjectResponse createProject(UUID workspaceId, CreateProjectRequest request);

    ProjectResponse getProject(UUID workspaceId, UUID projectId);

    List<ProjectResponse> getProjects(UUID workspaceId);

    ProjectResponse updateProject(
            UUID workspaceId,
            UUID projectId,
            UpdateProjectRequest request);

    void deleteProject(UUID workspaceId, UUID projectId);
}
