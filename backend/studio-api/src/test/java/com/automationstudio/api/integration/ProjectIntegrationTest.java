package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.ProjectStatus;
import com.automationstudio.api.dto.project.CreateProjectRequest;
import com.automationstudio.api.dto.project.UpdateProjectRequest;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.entity.Workspace;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.repository.WorkspaceRepository;
import com.automationstudio.api.source.SourceType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class ProjectIntegrationTest extends IntegrationTestBase {

    private static final String PROJECT_NAME = "Customer Portal";
    private static final String PROJECT_DESCRIPTION = "Customer-facing automation project";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void cleanDatabase() {
        projectRepository.deleteAll();
        workspaceRepository.deleteAll();
    }

    @Test
    void createProject_success() throws Exception {
        Workspace workspace = saveWorkspace();
        CreateProjectRequest request = new CreateProjectRequest(
                PROJECT_NAME, PROJECT_DESCRIPTION);

        mockMvc.perform(post(projectsPath(workspace.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.workspaceId").value(workspace.getId().toString()))
                .andExpect(jsonPath("$.name").value(PROJECT_NAME))
                .andExpect(jsonPath("$.description").value(PROJECT_DESCRIPTION))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(projectRepository.findAllByWorkspaceId(workspace.getId()))
                .singleElement()
                .satisfies(project -> {
                    assertThat(project.getName()).isEqualTo(PROJECT_NAME);
                    assertThat(project.getDescription()).isEqualTo(PROJECT_DESCRIPTION);
                    assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
                });
    }

    @Test
    void createAndUpdateProjectSourceConfigurationIsValidatedAndSanitized() throws Exception {
        Workspace workspace = saveWorkspace();
        CreateProjectRequest create = new CreateProjectRequest(
                PROJECT_NAME,
                PROJECT_DESCRIPTION,
                SourceType.GIT_HTTPS,
                "HTTPS://GitHub.COM/acme/automation.git",
                "ABCDEF0123456789ABCDEF0123456789ABCDEF01");

        String response = mockMvc.perform(post(projectsPath(workspace.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("GIT_HTTPS"))
                .andExpect(jsonPath("$.sourceRepository")
                        .value("https://github.com/acme/automation.git"))
                .andExpect(jsonPath("$.sourceRevision")
                        .value("abcdef0123456789abcdef0123456789abcdef01"))
                .andReturn().getResponse().getContentAsString();
        UUID projectId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        UpdateProjectRequest update = new UpdateProjectRequest(
                "Updated Portal",
                "Updated description",
                ProjectStatus.ACTIVE,
                SourceType.GIT_HTTPS,
                "https://github.com/acme/replacement.git",
                "2222222222222222222222222222222222222222");
        mockMvc.perform(put(projectPath(workspace.getId(), projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceRepository")
                        .value("https://github.com/acme/replacement.git"))
                .andExpect(jsonPath("$.sourceRevision")
                        .value("2222222222222222222222222222222222222222"));
    }

    @Test
    void credentialBearingRepositoryIsRejectedWithoutEchoingSensitiveValue() throws Exception {
        Workspace workspace = saveWorkspace();
        String secretRepository = "https://user:secret@example.test/repo.git";
        CreateProjectRequest request = new CreateProjectRequest(
                PROJECT_NAME,
                PROJECT_DESCRIPTION,
                SourceType.GIT_HTTPS,
                secretRepository,
                "1111111111111111111111111111111111111111");

        mockMvc.perform(post(projectsPath(workspace.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("user", "secret", secretRepository));
        assertThat(projectRepository.count()).isZero();
    }

    @Test
    void getProject_success() throws Exception {
        Workspace workspace = saveWorkspace();
        Project project = saveProject(workspace, PROJECT_NAME);

        mockMvc.perform(get(projectPath(workspace.getId(), project.getId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(project.getId().toString()))
                .andExpect(jsonPath("$.workspaceId").value(workspace.getId().toString()))
                .andExpect(jsonPath("$.name").value(PROJECT_NAME))
                .andExpect(jsonPath("$.description").value(PROJECT_DESCRIPTION))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void listProjects_success() throws Exception {
        Workspace workspace = saveWorkspace();
        saveProject(workspace, "Customer Portal");
        saveProject(workspace, "Operations Portal");

        mockMvc.perform(get(projectsPath(workspace.getId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void updateProject_success() throws Exception {
        Workspace workspace = saveWorkspace();
        Project project = saveProject(workspace, PROJECT_NAME);
        UpdateProjectRequest request = new UpdateProjectRequest(
                "Updated Portal", "Updated description", ProjectStatus.INACTIVE);

        mockMvc.perform(put(projectPath(workspace.getId(), project.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("Updated Portal"))
                .andExpect(jsonPath("$.description").value("Updated description"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        Project updatedProject = projectRepository.findById(project.getId()).orElseThrow();
        assertThat(updatedProject.getName()).isEqualTo("Updated Portal");
        assertThat(updatedProject.getDescription()).isEqualTo("Updated description");
        assertThat(updatedProject.getStatus()).isEqualTo(ProjectStatus.INACTIVE);
    }

    @Test
    void deleteProject_success() throws Exception {
        Workspace workspace = saveWorkspace();
        Project project = saveProject(workspace, PROJECT_NAME);

        mockMvc.perform(delete(projectPath(workspace.getId(), project.getId())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(projectRepository.findById(project.getId())).isEmpty();
    }

    @Test
    void duplicateProject_returns409() throws Exception {
        Workspace workspace = saveWorkspace();
        saveProject(workspace, PROJECT_NAME);
        CreateProjectRequest request = new CreateProjectRequest(
                PROJECT_NAME, PROJECT_DESCRIPTION);

        mockMvc.perform(post(projectsPath(workspace.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));

        assertThat(projectRepository.findAllByWorkspaceId(workspace.getId())).hasSize(1);
    }

    @Test
    void workspaceNotFound_returns404() throws Exception {
        UUID missingWorkspaceId = UUID.randomUUID();
        CreateProjectRequest request = new CreateProjectRequest(
                PROJECT_NAME, PROJECT_DESCRIPTION);

        mockMvc.perform(post(projectsPath(missingWorkspaceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));

        assertThat(projectRepository.count()).isZero();
    }

    @Test
    void validation_returns400() throws Exception {
        Workspace workspace = saveWorkspace();
        CreateProjectRequest request = new CreateProjectRequest(" ", PROJECT_DESCRIPTION);

        mockMvc.perform(post(projectsPath(workspace.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("name: Project name must not be blank"));

        assertThat(projectRepository.count()).isZero();
    }

    private Workspace saveWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setName("Integration Workspace");
        workspace.setSlug("integration-workspace");
        workspace.setDescription("Workspace for project integration tests");
        return workspaceRepository.save(workspace);
    }

    private Project saveProject(Workspace workspace, String name) {
        Project project = new Project();
        project.setWorkspace(workspace);
        project.setName(name);
        project.setDescription(PROJECT_DESCRIPTION);
        return projectRepository.save(project);
    }

    private String projectsPath(UUID workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/projects";
    }

    private String projectPath(UUID workspaceId, UUID projectId) {
        return projectsPath(workspaceId) + "/" + projectId;
    }
}
