package com.automationstudio.api.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.ProjectStatus;
import com.automationstudio.api.dto.project.CreateProjectRequest;
import com.automationstudio.api.dto.project.ProjectResponse;
import com.automationstudio.api.dto.project.UpdateProjectRequest;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.service.ProjectService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ProjectController.class)
@Import(GlobalExceptionHandler.class)
class ProjectControllerTest {

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
    private static final String BASE_PATH = "/api/v1/workspaces/" + WORKSPACE_ID + "/projects";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void returns201WhenProjectCreatedSuccessfully() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest(
                "Customer Portal", "Customer-facing automation project");
        ProjectResponse response = projectResponse(PROJECT_ID, "Customer Portal");
        when(projectService.createProject(WORKSPACE_ID, request)).thenReturn(response);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.workspaceId").value(WORKSPACE_ID.toString()))
                .andExpect(jsonPath("$.name").value("Customer Portal"))
                .andExpect(jsonPath("$.description").value("Project description"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-17T10:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-07-17T11:00:00Z"));

        verify(projectService).createProject(WORKSPACE_ID, request);
    }

    @Test
    void returns400WhenCreateRequestIsInvalid() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest(" ", null);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("name: Project name must not be blank"))
                .andExpect(jsonPath("$.path").value(BASE_PATH));

        verifyNoInteractions(projectService);
    }

    @Test
    void returns404WhenWorkspaceDoesNotExist() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("Customer Portal", null);
        String message = "Workspace not found with id: " + WORKSPACE_ID;
        when(projectService.createProject(WORKSPACE_ID, request))
                .thenThrow(new ResourceNotFoundException(message));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(BASE_PATH));

        verify(projectService).createProject(WORKSPACE_ID, request);
    }

    @Test
    void returns409WhenDuplicateProjectExists() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("Customer Portal", null);
        String message = "Project with name 'Customer Portal' already exists in workspace: "
                + WORKSPACE_ID;
        when(projectService.createProject(WORKSPACE_ID, request))
                .thenThrow(new DuplicateResourceException(message));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(BASE_PATH));

        verify(projectService).createProject(WORKSPACE_ID, request);
    }

    @Test
    void returnsProjectSuccessfully() throws Exception {
        ProjectResponse response = projectResponse(PROJECT_ID, "Customer Portal");
        when(projectService.getProject(WORKSPACE_ID, PROJECT_ID)).thenReturn(response);

        mockMvc.perform(get(projectPath(PROJECT_ID)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.workspaceId").value(WORKSPACE_ID.toString()))
                .andExpect(jsonPath("$.name").value("Customer Portal"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(projectService).getProject(WORKSPACE_ID, PROJECT_ID);
    }

    @Test
    void returns404WhenProjectDoesNotExist() throws Exception {
        String message = projectNotFoundMessage(PROJECT_ID);
        when(projectService.getProject(WORKSPACE_ID, PROJECT_ID))
                .thenThrow(new ResourceNotFoundException(message));

        mockMvc.perform(get(projectPath(PROJECT_ID)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(projectPath(PROJECT_ID)));

        verify(projectService).getProject(WORKSPACE_ID, PROJECT_ID);
    }

    @Test
    void returnsProjectsSuccessfully() throws Exception {
        ProjectResponse firstResponse = projectResponse(PROJECT_ID, "Customer Portal");
        ProjectResponse secondResponse = projectResponse(SECOND_PROJECT_ID, "Admin Portal");
        when(projectService.getProjects(WORKSPACE_ID))
                .thenReturn(List.of(firstResponse, secondResponse));

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("Customer Portal"))
                .andExpect(jsonPath("$[1].id").value(SECOND_PROJECT_ID.toString()))
                .andExpect(jsonPath("$[1].name").value("Admin Portal"));

        verify(projectService).getProjects(WORKSPACE_ID);
    }

    @Test
    void returnsEmptyArrayWhenNoProjectsExist() throws Exception {
        when(projectService.getProjects(WORKSPACE_ID)).thenReturn(List.of());

        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"));

        verify(projectService).getProjects(WORKSPACE_ID);
    }

    @Test
    void returnsUpdatedProjectSuccessfully() throws Exception {
        UpdateProjectRequest request = new UpdateProjectRequest(
                "Customer Portal", "Updated project", ProjectStatus.INACTIVE);
        ProjectResponse response = new ProjectResponse(
                PROJECT_ID,
                WORKSPACE_ID,
                "Customer Portal",
                "Updated project",
                ProjectStatus.INACTIVE,
                CREATED_AT,
                UPDATED_AT);
        when(projectService.updateProject(WORKSPACE_ID, PROJECT_ID, request))
                .thenReturn(response);

        mockMvc.perform(put(projectPath(PROJECT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.name").value("Customer Portal"))
                .andExpect(jsonPath("$.description").value("Updated project"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        verify(projectService).updateProject(WORKSPACE_ID, PROJECT_ID, request);
    }

    @Test
    void returns400WhenUpdateRequestIsInvalid() throws Exception {
        UpdateProjectRequest request = new UpdateProjectRequest(" ", null, null);

        mockMvc.perform(put(projectPath(PROJECT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(
                        "name: Project name must not be blank; "
                                + "status: Project status must not be null"))
                .andExpect(jsonPath("$.path").value(projectPath(PROJECT_ID)));

        verifyNoInteractions(projectService);
    }

    @Test
    void returns404WhenUpdatingMissingProject() throws Exception {
        UpdateProjectRequest request = new UpdateProjectRequest(
                "Customer Portal", null, ProjectStatus.ACTIVE);
        String message = projectNotFoundMessage(PROJECT_ID);
        when(projectService.updateProject(WORKSPACE_ID, PROJECT_ID, request))
                .thenThrow(new ResourceNotFoundException(message));

        mockMvc.perform(put(projectPath(PROJECT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(projectPath(PROJECT_ID)));

        verify(projectService).updateProject(WORKSPACE_ID, PROJECT_ID, request);
    }

    @Test
    void returns409WhenDuplicateProjectNameExists() throws Exception {
        UpdateProjectRequest request = new UpdateProjectRequest(
                "Admin Portal", null, ProjectStatus.ACTIVE);
        String message = "Project with name 'Admin Portal' already exists in workspace: "
                + WORKSPACE_ID;
        when(projectService.updateProject(WORKSPACE_ID, PROJECT_ID, request))
                .thenThrow(new DuplicateResourceException(message));

        mockMvc.perform(put(projectPath(PROJECT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(projectPath(PROJECT_ID)));

        verify(projectService).updateProject(WORKSPACE_ID, PROJECT_ID, request);
    }

    @Test
    void returns204WhenDeletedSuccessfully() throws Exception {
        mockMvc.perform(delete(projectPath(PROJECT_ID)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(projectService).deleteProject(WORKSPACE_ID, PROJECT_ID);
    }

    @Test
    void returns404WhenDeletingMissingProject() throws Exception {
        String message = projectNotFoundMessage(PROJECT_ID);
        doThrow(new ResourceNotFoundException(message))
                .when(projectService).deleteProject(WORKSPACE_ID, PROJECT_ID);

        mockMvc.perform(delete(projectPath(PROJECT_ID)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(projectPath(PROJECT_ID)));

        verify(projectService).deleteProject(WORKSPACE_ID, PROJECT_ID);
    }

    private String projectPath(UUID projectId) {
        return BASE_PATH + "/" + projectId;
    }

    private String projectNotFoundMessage(UUID projectId) {
        return "Project not found with id: " + projectId + " in workspace: " + WORKSPACE_ID;
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
