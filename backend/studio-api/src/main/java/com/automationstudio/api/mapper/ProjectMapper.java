package com.automationstudio.api.mapper;

import com.automationstudio.api.dto.project.CreateProjectRequest;
import com.automationstudio.api.dto.project.ProjectResponse;
import com.automationstudio.api.dto.project.UpdateProjectRequest;
import com.automationstudio.api.entity.Project;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ProjectMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workspace", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Project toEntity(CreateProjectRequest request);

    @Mapping(target = "workspaceId", source = "workspace.id")
    ProjectResponse toResponse(Project project);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workspace", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(UpdateProjectRequest request, @MappingTarget Project project);
}
