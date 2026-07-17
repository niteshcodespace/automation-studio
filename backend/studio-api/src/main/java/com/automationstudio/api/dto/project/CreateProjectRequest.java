package com.automationstudio.api.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank(message = "Project name must not be blank")
        @Size(max = 120, message = "Project name must not exceed 120 characters")
        String name,
        String description) {
}
