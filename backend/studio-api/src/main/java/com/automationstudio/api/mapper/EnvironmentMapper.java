package com.automationstudio.api.mapper;

import com.automationstudio.api.dto.environment.CreateEnvironmentRequest;
import com.automationstudio.api.dto.environment.EnvironmentResponse;
import com.automationstudio.api.dto.environment.UpdateEnvironmentRequest;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.service.command.CreateEnvironmentCommand;
import com.automationstudio.api.service.command.UpdateEnvironmentCommand;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EnvironmentMapper {

    CreateEnvironmentCommand toCommand(CreateEnvironmentRequest request);

    UpdateEnvironmentCommand toCommand(UpdateEnvironmentRequest request);

    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "isDefault", source = "default")
    EnvironmentResponse toResponse(Environment environment);

    default Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? null : new LinkedHashMap<>(source);
    }
}
