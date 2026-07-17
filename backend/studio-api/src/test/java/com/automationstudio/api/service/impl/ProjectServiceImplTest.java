package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.ProjectStatus;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    private static final UUID WORKSPACE_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_PROJECT_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse(
            "2026-07-17T10:00:00Z");
    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.parse(
            "2026-07-17T11:00:00Z");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private ProjectServiceImpl projectService;

    @Test
    void createsProjectSuccessfully() {
        Workspace workspace = new Workspace();
        Project project = new Project();
        CreateProjectRequest request = new CreateProjectRequest(
                "  Customer Portal  ", "Customer-facing automation project");
        ProjectResponse expectedResponse = projectResponse(PROJECT_ID, "Customer Portal");

        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(projectRepository.existsByWorkspaceIdAndName(WORKSPACE_ID, "Customer Portal"))
                .thenReturn(false);
        when(projectMapper.toEntity(request)).thenReturn(project);
        when(projectRepository.save(project)).thenReturn(project);
        when(projectMapper.toResponse(project)).thenReturn(expectedResponse);

        ProjectResponse response = projectService.createProject(WORKSPACE_ID, request);

        assertThat(response).isSameAs(expectedResponse);
        assertThat(project.getName()).isEqualTo("Customer Portal");
        assertThat(project.getWorkspace()).isSameAs(workspace);
        verify(projectRepository).save(project);
        verify(projectMapper).toEntity(request);
        verify(projectMapper).toResponse(project);
    }

    @Test
    void throwsResourceNotFoundExceptionWhenWorkspaceDoesNotExistDuringCreate() {
        CreateProjectRequest request = new CreateProjectRequest("Customer Portal", null);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> projectService.createProject(WORKSPACE_ID, request))
                .withMessage("Workspace not found with id: " + WORKSPACE_ID);

        verifyNoInteractions(projectRepository, projectMapper);
    }

    @Test
    void throwsDuplicateResourceExceptionWhenProjectNameAlreadyExists() {
        Workspace workspace = new Workspace();
        CreateProjectRequest request = new CreateProjectRequest("  Customer Portal  ", null);
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(projectRepository.existsByWorkspaceIdAndName(WORKSPACE_ID, "Customer Portal"))
                .thenReturn(true);

        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> projectService.createProject(WORKSPACE_ID, request))
                .withMessage("Project with name 'Customer Portal' already exists in workspace: "
                        + WORKSPACE_ID);

        verifyNoInteractions(projectMapper);
        verify(projectRepository, never()).save(any(Project.class));
    }

    @Test
    void returnsProjectWhenFound() {
        Project project = project(PROJECT_ID, "Customer Portal");
        ProjectResponse expectedResponse = projectResponse(PROJECT_ID, "Customer Portal");
        when(projectRepository.findByIdAndWorkspaceId(PROJECT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(project));
        when(projectMapper.toResponse(project)).thenReturn(expectedResponse);

        ProjectResponse response = projectService.getProject(WORKSPACE_ID, PROJECT_ID);

        assertThat(response).isSameAs(expectedResponse);
        verify(projectMapper).toResponse(project);
    }

    @Test
    void throwsResourceNotFoundExceptionWhenProjectDoesNotExist() {
        when(projectRepository.findByIdAndWorkspaceId(PROJECT_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> projectService.getProject(WORKSPACE_ID, PROJECT_ID))
                .withMessage("Project not found with id: " + PROJECT_ID
                        + " in workspace: " + WORKSPACE_ID);

        verifyNoInteractions(projectMapper);
    }

    @Test
    void returnsProjectsForWorkspace() {
        Project firstProject = project(PROJECT_ID, "Customer Portal");
        Project secondProject = project(SECOND_PROJECT_ID, "Admin Portal");
        ProjectResponse firstResponse = projectResponse(PROJECT_ID, "Customer Portal");
        ProjectResponse secondResponse = projectResponse(SECOND_PROJECT_ID, "Admin Portal");
        when(workspaceRepository.existsById(WORKSPACE_ID)).thenReturn(true);
        when(projectRepository.findAllByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(firstProject, secondProject));
        when(projectMapper.toResponse(firstProject)).thenReturn(firstResponse);
        when(projectMapper.toResponse(secondProject)).thenReturn(secondResponse);

        List<ProjectResponse> responses = projectService.getProjects(WORKSPACE_ID);

        assertThat(responses).containsExactly(firstResponse, secondResponse);
        verify(projectMapper).toResponse(firstProject);
        verify(projectMapper).toResponse(secondProject);
    }

    @Test
    void returnsEmptyListWhenWorkspaceHasNoProjects() {
        when(workspaceRepository.existsById(WORKSPACE_ID)).thenReturn(true);
        when(projectRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of());

        List<ProjectResponse> responses = projectService.getProjects(WORKSPACE_ID);

        assertThat(responses).isEmpty();
        verifyNoInteractions(projectMapper);
    }

    @Test
    void throwsResourceNotFoundExceptionWhenWorkspaceDoesNotExistDuringList() {
        when(workspaceRepository.existsById(WORKSPACE_ID)).thenReturn(false);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> projectService.getProjects(WORKSPACE_ID))
                .withMessage("Workspace not found with id: " + WORKSPACE_ID);

        verify(projectRepository, never()).findAllByWorkspaceId(WORKSPACE_ID);
        verifyNoInteractions(projectMapper);
    }

    @Test
    void updatesProjectSuccessfully() {
        Project project = project(PROJECT_ID, "Customer Portal");
        UpdateProjectRequest request = new UpdateProjectRequest(
                "  Internal Portal  ", "Updated project", ProjectStatus.INACTIVE);
        ProjectResponse expectedResponse = projectResponse(PROJECT_ID, "Internal Portal");
        when(projectRepository.findByIdAndWorkspaceId(PROJECT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(project));
        when(projectRepository.existsByWorkspaceIdAndName(WORKSPACE_ID, "Internal Portal"))
                .thenReturn(false);
        when(projectRepository.save(project)).thenReturn(project);
        when(projectMapper.toResponse(project)).thenReturn(expectedResponse);

        ProjectResponse response = projectService.updateProject(
                WORKSPACE_ID, PROJECT_ID, request);

        assertThat(response).isSameAs(expectedResponse);
        assertThat(project.getName()).isEqualTo("Internal Portal");
        verify(projectMapper).updateEntity(request, project);
        verify(projectRepository).save(project);
        verify(projectMapper).toResponse(project);
    }

    @Test
    void updatesProjectWithoutDuplicateCheckWhenNameIsUnchanged() {
        Project project = project(PROJECT_ID, "Customer Portal");
        UpdateProjectRequest request = new UpdateProjectRequest(
                "  Customer Portal  ", "Updated project", ProjectStatus.ARCHIVED);
        ProjectResponse expectedResponse = projectResponse(PROJECT_ID, "Customer Portal");
        when(projectRepository.findByIdAndWorkspaceId(PROJECT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(project));
        when(projectRepository.save(project)).thenReturn(project);
        when(projectMapper.toResponse(project)).thenReturn(expectedResponse);

        ProjectResponse response = projectService.updateProject(
                WORKSPACE_ID, PROJECT_ID, request);

        assertThat(response).isSameAs(expectedResponse);
        assertThat(project.getName()).isEqualTo("Customer Portal");
        verify(projectRepository, never()).existsByWorkspaceIdAndName(any(), any());
        verify(projectMapper).updateEntity(request, project);
        verify(projectRepository).save(project);
    }

    @Test
    void throwsDuplicateResourceExceptionWhenUpdatedNameAlreadyExists() {
        Project project = project(PROJECT_ID, "Customer Portal");
        UpdateProjectRequest request = new UpdateProjectRequest(
                "  Admin Portal  ", null, ProjectStatus.ACTIVE);
        when(projectRepository.findByIdAndWorkspaceId(PROJECT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(project));
        when(projectRepository.existsByWorkspaceIdAndName(WORKSPACE_ID, "Admin Portal"))
                .thenReturn(true);

        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> projectService.updateProject(
                        WORKSPACE_ID, PROJECT_ID, request))
                .withMessage("Project with name 'Admin Portal' already exists in workspace: "
                        + WORKSPACE_ID);

        verifyNoInteractions(projectMapper);
        verify(projectRepository, never()).save(any(Project.class));
    }

    @Test
    void throwsResourceNotFoundExceptionWhenUpdatingMissingProject() {
        UpdateProjectRequest request = new UpdateProjectRequest(
                "Customer Portal", null, ProjectStatus.ACTIVE);
        when(projectRepository.findByIdAndWorkspaceId(PROJECT_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> projectService.updateProject(
                        WORKSPACE_ID, PROJECT_ID, request))
                .withMessage("Project not found with id: " + PROJECT_ID
                        + " in workspace: " + WORKSPACE_ID);

        verify(projectRepository, never()).existsByWorkspaceIdAndName(any(), any());
        verify(projectRepository, never()).save(any(Project.class));
        verifyNoInteractions(projectMapper);
    }

    @Test
    void deletesProjectSuccessfully() {
        Project project = project(PROJECT_ID, "Customer Portal");
        when(projectRepository.findByIdAndWorkspaceId(PROJECT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(project));

        projectService.deleteProject(WORKSPACE_ID, PROJECT_ID);

        verify(projectRepository).delete(project);
    }

    @Test
    void throwsResourceNotFoundExceptionWhenDeletingMissingProject() {
        when(projectRepository.findByIdAndWorkspaceId(PROJECT_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> projectService.deleteProject(WORKSPACE_ID, PROJECT_ID))
                .withMessage("Project not found with id: " + PROJECT_ID
                        + " in workspace: " + WORKSPACE_ID);

        verify(projectRepository, never()).delete(any(Project.class));
    }

    private Project project(UUID projectId, String name) {
        Project project = new Project();
        project.setId(projectId);
        project.setName(name);
        project.setStatus(ProjectStatus.ACTIVE);
        return project;
    }

    private ProjectResponse projectResponse(UUID projectId, String name) {
        return new ProjectResponse(
                projectId,
                WORKSPACE_ID,
                name,
                "Project description",
                ProjectStatus.ACTIVE,
                CREATED_AT,
                UPDATED_AT);
    }
}
