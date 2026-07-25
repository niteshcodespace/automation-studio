package com.automationstudio.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.dto.execution.CreateExecutionRequest;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.entity.Project;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

class ExecutionMapperTest {

    private final ExecutionMapper mapper = Mappers.getMapper(ExecutionMapper.class);

    @Test
    void mapsCreateCommandAndProjectScopedResponse() {
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        CreateExecutionRequest request = new CreateExecutionRequest(
                environmentId, suiteId, ExecutionSelectionMode.TEST_CASES, List.of(caseId));

        assertThat(mapper.toCommand(request).testCaseIds()).containsExactly(caseId);

        Project project = new Project();
        Environment environment = new Environment();
        AutomationSuite suite = new AutomationSuite();
        ReflectionTestUtils.setField(project, "id", projectId);
        ReflectionTestUtils.setField(environment, "id", environmentId);
        ReflectionTestUtils.setField(suite, "id", suiteId);
        Execution execution = new Execution();
        execution.setProject(project);
        execution.setEnvironment(environment);
        execution.setAutomationSuite(suite);

        assertThat(mapper.toResponse(execution))
                .extracting("projectId", "environmentId", "automationSuiteId")
                .containsExactly(projectId, environmentId, suiteId);
    }
}
