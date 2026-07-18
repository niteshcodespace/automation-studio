package com.automationstudio.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.domain.SuiteType;
import com.automationstudio.api.dto.automation.suite.AutomationSuiteResponse;
import com.automationstudio.api.dto.automation.suite.CreateAutomationSuiteRequest;
import com.automationstudio.api.dto.automation.suite.UpdateAutomationSuiteRequest;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Project;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class AutomationSuiteMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID SUITE_ID = UUID.fromString(
            "30000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse(
            "2026-07-17T10:00:00Z");
    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.parse(
            "2026-07-17T11:00:00Z");

    private final AutomationSuiteMapper mapper =
            Mappers.getMapper(AutomationSuiteMapper.class);

    @Test
    void mapsCreateRequestFieldsAndIgnoresServerControlledFields() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("browser", "chromium");
        CreateAutomationSuiteRequest request = new CreateAutomationSuiteRequest(
                "Checkout suite", "Critical tests", "PLAYWRIGHT", "tests/checkout",
                "playwright-java", SuiteType.UI, configuration,
                AutomationSuiteStatus.ARCHIVED);

        AutomationSuite suite = mapper.toEntity(request);

        assertThat(suite.getName()).isEqualTo("Checkout suite");
        assertThat(suite.getDescription()).isEqualTo("Critical tests");
        assertThat(suite.getEngineType()).isEqualTo("PLAYWRIGHT");
        assertThat(suite.getSuiteReference()).isEqualTo("tests/checkout");
        assertThat(suite.getEngineId()).isEqualTo("playwright-java");
        assertThat(suite.getSuiteType()).isEqualTo(SuiteType.UI);
        assertThat(suite.getStatus()).isEqualTo(AutomationSuiteStatus.ARCHIVED);
        assertThat(suite.getConfiguration()).containsEntry("browser", "chromium");
        assertThat(suite.getId()).isNull();
        assertThat(suite.getProject()).isNull();
        assertThat(suite.getVersion()).isZero();
        assertThat(suite.getCreatedAt()).isNull();
        assertThat(suite.getUpdatedAt()).isNull();

        configuration.put("browser", "firefox");
        assertThat(suite.getConfiguration()).containsEntry("browser", "chromium");
    }

    @Test
    void mapsUpdateFieldsWithoutAllowingStatusOrServerControlledFields() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("retries", 2);
        UpdateAutomationSuiteRequest request = new UpdateAutomationSuiteRequest(
                "Regression suite", "Regression tests", "SELENIUM", "tests/regression",
                "selenium-java", SuiteType.UI, configuration);

        AutomationSuite suite = mapper.toEntity(request);

        assertThat(suite.getName()).isEqualTo("Regression suite");
        assertThat(suite.getDescription()).isEqualTo("Regression tests");
        assertThat(suite.getEngineType()).isEqualTo("SELENIUM");
        assertThat(suite.getSuiteReference()).isEqualTo("tests/regression");
        assertThat(suite.getEngineId()).isEqualTo("selenium-java");
        assertThat(suite.getSuiteType()).isEqualTo(SuiteType.UI);
        assertThat(suite.getConfiguration()).containsEntry("retries", 2);
        assertThat(suite.getStatus()).isEqualTo(AutomationSuiteStatus.ACTIVE);
        assertThat(suite.getId()).isNull();
        assertThat(suite.getProject()).isNull();
        assertThat(suite.getVersion()).isZero();
        assertThat(suite.getCreatedAt()).isNull();
        assertThat(suite.getUpdatedAt()).isNull();
    }

    @Test
    void mapsEntityResponseOwnershipVersionTimestampsAndConfigurationSafely() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        AutomationSuite suite = new AutomationSuite();
        suite.setId(SUITE_ID);
        suite.setProject(project);
        suite.setName("Checkout suite");
        suite.setDescription("Critical tests");
        suite.setEngineType("PLAYWRIGHT");
        suite.setSuiteReference("tests/checkout");
        suite.setEngineId("playwright-java");
        suite.setSuiteType(SuiteType.UI);
        suite.setConfiguration(new LinkedHashMap<>(Map.of("browser", "chromium")));
        suite.setStatus(AutomationSuiteStatus.INACTIVE);
        suite.setVersion(7);
        suite.setCreatedAt(CREATED_AT);
        suite.setUpdatedAt(UPDATED_AT);

        AutomationSuiteResponse response = mapper.toResponse(suite);

        assertThat(response.id()).isEqualTo(SUITE_ID);
        assertThat(response.projectId()).isEqualTo(PROJECT_ID);
        assertThat(response.name()).isEqualTo("Checkout suite");
        assertThat(response.engineId()).isEqualTo("playwright-java");
        assertThat(response.status()).isEqualTo(AutomationSuiteStatus.INACTIVE);
        assertThat(response.version()).isEqualTo(7);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
        assertThat(response.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(response.configuration()).containsEntry("browser", "chromium");

        response.configuration().put("browser", "firefox");
        assertThat(suite.getConfiguration()).containsEntry("browser", "chromium");
    }
}
