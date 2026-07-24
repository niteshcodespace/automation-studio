package com.automationstudio.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.dto.environment.CreateEnvironmentRequest;
import com.automationstudio.api.dto.environment.EnvironmentResponse;
import com.automationstudio.api.dto.environment.UpdateEnvironmentRequest;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.service.command.CreateEnvironmentCommand;
import com.automationstudio.api.service.command.UpdateEnvironmentCommand;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class EnvironmentMapperTest {

    private static final UUID PROJECT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ENVIRONMENT_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000001");

    private final EnvironmentMapper mapper = Mappers.getMapper(EnvironmentMapper.class);

    @Test
    void mapsCompleteCreateRequestToServiceCommandDefensively() {
        Map<String, Object> configuration = new LinkedHashMap<>(Map.of("region", "eu-west-1"));
        Map<String, Object> references =
                new LinkedHashMap<>(Map.of("token", "vault://team/token"));
        CreateEnvironmentRequest request = new CreateEnvironmentRequest(
                "QA", "Quality assurance", "https://qa.example.test", EnvironmentType.QA,
                configuration, references, EnvironmentStatus.ACTIVE, true);

        CreateEnvironmentCommand command = mapper.toCommand(request);

        assertThat(command.name()).isEqualTo("QA");
        assertThat(command.type()).isEqualTo(EnvironmentType.QA);
        assertThat(command.status()).isEqualTo(EnvironmentStatus.ACTIVE);
        assertThat(command.isDefault()).isTrue();
        configuration.put("region", "changed");
        references.put("token", "changed");
        assertThat(command.configuration()).containsEntry("region", "eu-west-1");
        assertThat(command.secretReferences()).containsEntry("token", "vault://team/token");
    }

    @Test
    void preservesNullCreateDefaultsAndMapsUpdateFields() {
        CreateEnvironmentCommand create = mapper.toCommand(new CreateEnvironmentRequest(
                "Test", null, "https://test.example", EnvironmentType.TEST,
                null, null, null, null));
        UpdateEnvironmentCommand update = mapper.toCommand(new UpdateEnvironmentRequest(
                "Test", null, "https://test.example", EnvironmentType.TEST,
                null, null));

        assertThat(create.configuration()).isNull();
        assertThat(create.secretReferences()).isNull();
        assertThat(create.status()).isNull();
        assertThat(create.isDefault()).isNull();
        assertThat(update.configuration()).isNull();
        assertThat(update.secretReferences()).isNull();
    }

    @Test
    void mapsResponseOwnershipVersionTimestampsAndMapsDefensively() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        Environment environment = new Environment();
        environment.setProject(project);
        environment.setName("Production");
        environment.setDescription("Production target");
        environment.setBaseUrl("https://example.com");
        environment.setType(EnvironmentType.PRODUCTION);
        environment.setConfiguration(Map.of("region", "us-east-1"));
        environment.setSecretReferences(Map.of("token", "vault://prod/token"));
        environment.setStatus(EnvironmentStatus.ACTIVE);
        environment.setDefault(true);
        environment.setVersion(4);
        environment.setCreatedAt(OffsetDateTime.parse("2026-07-24T10:00:00Z"));
        environment.setUpdatedAt(OffsetDateTime.parse("2026-07-24T11:00:00Z"));
        setId(environment, ENVIRONMENT_ID);

        EnvironmentResponse response = mapper.toResponse(environment);

        assertThat(response.id()).isEqualTo(ENVIRONMENT_ID);
        assertThat(response.projectId()).isEqualTo(PROJECT_ID);
        assertThat(response.type()).isEqualTo(EnvironmentType.PRODUCTION);
        assertThat(response.isDefault()).isTrue();
        assertThat(response.version()).isEqualTo(4);
        response.configuration().put("region", "changed");
        response.secretReferences().put("token", "changed");
        assertThat(environment.getConfiguration()).containsEntry("region", "us-east-1");
        assertThat(environment.getSecretReferences()).containsEntry("token", "vault://prod/token");
    }

    private void setId(Environment environment, UUID id) {
        try {
            var field = Environment.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(environment, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
