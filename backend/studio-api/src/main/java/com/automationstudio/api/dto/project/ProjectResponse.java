package com.automationstudio.api.dto.project;

import com.automationstudio.api.domain.ProjectStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String description,
        ProjectStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
