package com.automationstudio.api.mapper;

import com.automationstudio.api.dto.automation.testcase.AutomationTestCaseResponse;
import com.automationstudio.api.dto.automation.testcase.CreateAutomationTestCaseRequest;
import com.automationstudio.api.dto.automation.testcase.UpdateAutomationTestCaseRequest;
import com.automationstudio.api.entity.AutomationTestCase;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AutomationTestCaseMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "automationSuite", ignore = true)
    @Mapping(target = "position", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    AutomationTestCase toEntity(CreateAutomationTestCaseRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "automationSuite", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "position", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    AutomationTestCase toEntity(UpdateAutomationTestCaseRequest request);

    @Mapping(target = "automationSuiteId", source = "automationSuite.id")
    AutomationTestCaseResponse toResponse(AutomationTestCase testCase);
}
