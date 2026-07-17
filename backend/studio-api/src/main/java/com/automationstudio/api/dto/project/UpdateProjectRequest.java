package com.automationstudio.api.dto.project;

import com.automationstudio.api.domain.ProjectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @NotBlank(message = "Project name must not be blank")
        @Size(max = 120, message = "Project name must not exceed 120 characters")
        String name,
        String description,
        @NotNull(message = "Project status must not be null")
        ProjectStatus status) {
}
