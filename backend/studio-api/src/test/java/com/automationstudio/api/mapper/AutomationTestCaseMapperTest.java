package com.automationstudio.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.dto.automation.testcase.AutomationTestCaseResponse;
import com.automationstudio.api.dto.automation.testcase.CreateAutomationTestCaseRequest;
import com.automationstudio.api.dto.automation.testcase.UpdateAutomationTestCaseRequest;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.AutomationTestCase;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class AutomationTestCaseMapperTest {

    private final AutomationTestCaseMapper mapper = Mappers.getMapper(AutomationTestCaseMapper.class);

    @Test
    void createMapsAllRequestFieldsAndIgnoresEveryServerControlledField() {
        Map<String, Object> configuration = new LinkedHashMap<>(Map.of("browser", "chromium"));
        AutomationTestCase entity = mapper.toEntity(new CreateAutomationTestCaseRequest(
                "Case", "Description", "native case", configuration,
                AutomationTestCaseStatus.ARCHIVED));

        assertThat(entity.getName()).isEqualTo("Case");
        assertThat(entity.getDescription()).isEqualTo("Description");
        assertThat(entity.getCaseReference()).isEqualTo("native case");
        assertThat(entity.getConfiguration()).containsExactlyEntriesOf(Map.of("browser", "chromium"));
        assertThat(entity.getStatus()).isEqualTo(AutomationTestCaseStatus.ARCHIVED);
        assertIgnoredServerFields(entity);

        configuration.put("browser", "firefox");
        assertThat(entity.getConfiguration()).containsEntry("browser", "chromium");
    }

    @Test
    void createPreservesNullOptionalFieldsAndNullStatusForServiceDefaulting() {
        AutomationTestCase entity = mapper.toEntity(
                new CreateAutomationTestCaseRequest("Case", null, "ref", null, null));

        assertThat(entity.getDescription()).isNull();
        assertThat(entity.getConfiguration()).isNull();
        assertThat(entity.getStatus()).isNull();
        assertIgnoredServerFieldsExceptStatus(entity);
    }

    @Test
    void updateMapsMutableFieldsAndIgnoresEveryServerControlledField() {
        Map<String, Object> configuration = new LinkedHashMap<>(Map.of("retries", 2));
        AutomationTestCase entity = mapper.toEntity(new UpdateAutomationTestCaseRequest(
                "Updated", "Updated description", "updated-ref", configuration));

        assertThat(entity.getName()).isEqualTo("Updated");
        assertThat(entity.getDescription()).isEqualTo("Updated description");
        assertThat(entity.getCaseReference()).isEqualTo("updated-ref");
        assertThat(entity.getConfiguration()).containsExactlyEntriesOf(Map.of("retries", 2));
        assertThat(entity.getStatus()).isEqualTo(AutomationTestCaseStatus.ACTIVE);
        assertIgnoredServerFieldsExceptStatus(entity);

        configuration.put("retries", 9);
        assertThat(entity.getConfiguration()).containsEntry("retries", 2);
    }

    @Test
    void updatePreservesNullOptionalFields() {
        AutomationTestCase entity = mapper.toEntity(
                new UpdateAutomationTestCaseRequest("Updated", null, "ref", null));

        assertThat(entity.getDescription()).isNull();
        assertThat(entity.getConfiguration()).isNull();
        assertThat(entity.getStatus()).isEqualTo(AutomationTestCaseStatus.ACTIVE);
        assertIgnoredServerFieldsExceptStatus(entity);
    }

    @Test
    void responseMapsEveryFieldAndDoesNotExposeEntityConfiguration() {
        UUID suiteId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-19T10:00:00Z");
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-07-19T11:00:00Z");
        AutomationSuite suite = new AutomationSuite();
        suite.setId(suiteId);
        AutomationTestCase entity = new AutomationTestCase();
        entity.setId(caseId);
        entity.setAutomationSuite(suite);
        entity.setName("Case");
        entity.setDescription("Description");
        entity.setCaseReference("case-ref");
        entity.setConfiguration(Map.of("browser", "chromium"));
        entity.setStatus(AutomationTestCaseStatus.INACTIVE);
        entity.setPosition(3);
        entity.setVersion(4);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);

        AutomationTestCaseResponse response = mapper.toResponse(entity);

        assertThat(response.id()).isEqualTo(caseId);
        assertThat(response.automationSuiteId()).isEqualTo(suiteId);
        assertThat(response.name()).isEqualTo("Case");
        assertThat(response.description()).isEqualTo("Description");
        assertThat(response.caseReference()).isEqualTo("case-ref");
        assertThat(response.configuration()).containsExactlyEntriesOf(Map.of("browser", "chromium"));
        assertThat(response.status()).isEqualTo(AutomationTestCaseStatus.INACTIVE);
        assertThat(response.position()).isEqualTo(3);
        assertThat(response.version()).isEqualTo(4);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);

        response.configuration().put("browser", "firefox");
        assertThat(entity.getConfiguration()).containsEntry("browser", "chromium");
    }

    @Test
    void responsePreservesNullableDescriptionAndConfiguration() {
        AutomationSuite suite = new AutomationSuite();
        suite.setId(UUID.randomUUID());
        AutomationTestCase entity = new AutomationTestCase();
        entity.setAutomationSuite(suite);
        entity.setDescription(null);
        entity.setConfiguration(null);

        AutomationTestCaseResponse response = mapper.toResponse(entity);

        assertThat(response.description()).isNull();
        assertThat(response.configuration()).isNull();
        assertThat(response.automationSuiteId()).isEqualTo(suite.getId());
    }

    private void assertIgnoredServerFields(AutomationTestCase entity) {
        assertIgnoredServerFieldsExceptStatus(entity);
    }

    private void assertIgnoredServerFieldsExceptStatus(AutomationTestCase entity) {
        assertThat(entity.getId()).isNull();
        assertThat(entity.getAutomationSuite()).isNull();
        assertThat(entity.getPosition()).isNull();
        assertThat(entity.getVersion()).isZero();
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
    }
}
