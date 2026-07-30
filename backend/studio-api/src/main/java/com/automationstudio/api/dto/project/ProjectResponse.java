package com.automationstudio.api.dto.project;

import com.automationstudio.api.domain.ProjectStatus;
import com.automationstudio.api.source.SourceType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String description,
        ProjectStatus status,
        SourceType sourceType,
        String sourceRepository,
        String sourceRevision,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public ProjectResponse(
            UUID id,
            UUID workspaceId,
            String name,
            String description,
            ProjectStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this(id, workspaceId, name, description, status, null, null, null,
                createdAt, updatedAt);
    }
}
