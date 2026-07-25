package com.automationstudio.api.mapper;

import com.automationstudio.api.dto.execution.CreateExecutionRequest;
import com.automationstudio.api.dto.execution.ExecutionResponse;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.service.command.CreateExecutionCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ExecutionMapper {

    CreateExecutionCommand toCommand(CreateExecutionRequest request);

    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "environmentId", source = "environment.id")
    @Mapping(target = "automationSuiteId", source = "automationSuite.id")
    ExecutionResponse toResponse(Execution execution);
}
