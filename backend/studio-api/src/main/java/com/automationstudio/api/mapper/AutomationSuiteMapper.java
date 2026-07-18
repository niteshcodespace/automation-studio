package com.automationstudio.api.mapper;

import com.automationstudio.api.dto.automation.suite.AutomationSuiteResponse;
import com.automationstudio.api.dto.automation.suite.CreateAutomationSuiteRequest;
import com.automationstudio.api.dto.automation.suite.UpdateAutomationSuiteRequest;
import com.automationstudio.api.entity.AutomationSuite;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AutomationSuiteMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    AutomationSuite toEntity(CreateAutomationSuiteRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    AutomationSuite toEntity(UpdateAutomationSuiteRequest request);

    @Mapping(target = "projectId", source = "project.id")
    AutomationSuiteResponse toResponse(AutomationSuite suite);
}
