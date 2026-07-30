package com.automationstudio.api.dto.project;

import com.automationstudio.api.source.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank(message = "Project name must not be blank")
        @Size(max = 120, message = "Project name must not exceed 120 characters")
        String name,
        String description,
        SourceType sourceType,
        String sourceRepository,
        String sourceRevision) {

    public CreateProjectRequest(String name, String description) {
        this(name, description, null, null, null);
    }
}
