package com.automationstudio.api.controller;

import com.automationstudio.api.dto.project.CreateProjectRequest;
import com.automationstudio.api.dto.project.ProjectResponse;
import com.automationstudio.api.dto.project.UpdateProjectRequest;
import com.automationstudio.api.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse response = projectService.createProject(workspaceId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> getProjects(
            @PathVariable UUID workspaceId) {
        return ResponseEntity.ok(projectService.getProjects(workspaceId));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> getProject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectService.getProject(workspaceId, projectId));
    }

    @PutMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> updateProject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request) {
        return ResponseEntity.ok(projectService.updateProject(workspaceId, projectId, request));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {
        projectService.deleteProject(workspaceId, projectId);
        return ResponseEntity.noContent().build();
    }
}
