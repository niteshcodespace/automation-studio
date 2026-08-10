package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.artifact.metadata.ArtifactMetadataException;
import com.automationstudio.api.execution.artifact.metadata.ArtifactMetadataService;
import com.automationstudio.api.execution.artifact.metadata.ArtifactRegistration;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageKey;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageReference;
import com.automationstudio.api.execution.artifact.storage.StoredArtifact;
import com.automationstudio.engine.sdk.ArtifactCategory;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ArtifactMetadataServiceIntegrationTest extends IntegrationTestBase {

    @Autowired ArtifactMetadataService service;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM execution_artifact WHERE logical_name LIKE 'as-028d-%'");
        jdbcTemplate.update("DELETE FROM execution WHERE requested_by = 'as-028d-service-test'");
        jdbcTemplate.update("DELETE FROM test_suite WHERE name LIKE 'AS-028D Service Suite%'");
        jdbcTemplate.update("DELETE FROM environment WHERE name LIKE 'AS-028D Service Environment%'");
        jdbcTemplate.update("DELETE FROM project WHERE name LIKE 'AS-028D Service Project%'");
        jdbcTemplate.update("DELETE FROM workspace WHERE slug LIKE 'as-028d-service-%'");
    }

    @Test
    void registersImmutableMetadataAndDiscoversInDeterministicOrder() {
        Fixture fixture = insertFixture();
        ArtifactRegistration later = registration("as-028d-later.log",
                Instant.parse("2026-08-10T01:00:00Z"), Map.of("step", "two"));
        ArtifactRegistration earlier = registration("as-028d-earlier.log",
                Instant.parse("2026-08-10T00:00:00Z"), Map.of("step", "one"));
        service.register(fixture.workspaceId(), fixture.projectId(), fixture.executionId(), later);
        var registered = service.register(
                fixture.workspaceId(), fixture.projectId(), fixture.executionId(), earlier);

        assertThat(registered.category()).isEqualTo("vendor.custom");
        assertThat(registered.checksumAlgorithm()).isEqualTo("SHA-256");
        assertThat(registered.storageReference()).startsWith("artifact:v1:");
        assertThat(registered.retentionReference()).isEqualTo("retain:default");
        assertThat(registered.metadata()).containsExactlyEntriesOf(Map.of("step", "one"));
        assertThatThrownBy(() -> registered.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(service.list(fixture.workspaceId(), fixture.projectId(), fixture.executionId()))
                .extracting(item -> item.logicalName())
                .containsExactly("as-028d-earlier.log", "as-028d-later.log");
        assertThat(service.find(fixture.workspaceId(), fixture.projectId(), fixture.executionId(),
                earlier.artifactId())).contains(registered);
    }

    @Test
    void rejectsDuplicatesAndFailedRegistrationIsNotDiscoverable() {
        Fixture fixture = insertFixture();
        ArtifactRegistration registration = registration(
                "as-028d-duplicate.log", Instant.now(), Map.of());
        service.register(fixture.workspaceId(), fixture.projectId(), fixture.executionId(), registration);
        assertThatThrownBy(() -> service.register(
                fixture.workspaceId(), fixture.projectId(), fixture.executionId(), registration))
                .isInstanceOf(ArtifactMetadataException.class)
                .hasMessage("Artifact metadata is already registered");
        assertThat(service.list(fixture.workspaceId(), fixture.projectId(), fixture.executionId()))
                .hasSize(1);
    }

    @Test
    void enforcesWorkspaceProjectExecutionAndArtifactScope() {
        Fixture first = insertFixture();
        Fixture second = insertFixture();
        ArtifactRegistration registration = registration("as-028d-scoped.log", Instant.now(), Map.of());
        service.register(first.workspaceId(), first.projectId(), first.executionId(), registration);

        assertThat(service.find(second.workspaceId(), second.projectId(), second.executionId(),
                registration.artifactId())).isEmpty();
        assertThat(service.find(first.workspaceId(), first.projectId(), second.executionId(),
                registration.artifactId())).isEmpty();
        assertThatThrownBy(() -> service.list(
                second.workspaceId(), first.projectId(), first.executionId()))
                .isInstanceOf(ArtifactMetadataException.class)
                .hasMessage("Artifact scope was not found");
    }

    private ArtifactRegistration registration(
            String logicalName, Instant createdAt, Map<String, String> metadata) {
        UUID artifactId = UUID.randomUUID();
        var stored = new StoredArtifact(ArtifactStorageReference.from(
                new ArtifactStorageKey(artifactId)), 3, "SHA-256", "a".repeat(64), createdAt);
        return new ArtifactRegistration(artifactId, new ArtifactCategory("vendor.custom"),
                logicalName, "text/plain", metadata, stored, "retain:default");
    }

    private Fixture insertFixture() {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID suite = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO workspace (id,name,slug,status) VALUES (?,?,?,'ACTIVE')",
                workspace, "AS-028D Service Workspace", "as-028d-service-" + workspace);
        jdbcTemplate.update("INSERT INTO project (id,workspace_id,name,status) VALUES (?,?,?,'ACTIVE')",
                project, workspace, "AS-028D Service Project " + project);
        jdbcTemplate.update("""
                INSERT INTO environment (id,project_id,name,base_url,type,status)
                VALUES (?,?,?,'https://example.test','TEST','ACTIVE')
                """, environment, project, "AS-028D Service Environment " + environment);
        jdbcTemplate.update("""
                INSERT INTO test_suite (id,project_id,name,engine_type,suite_reference,status)
                VALUES (?,?,?,'PLAYWRIGHT',?,'ACTIVE')
                """, suite, project, "AS-028D Service Suite " + suite, "tests/" + suite);
        jdbcTemplate.update("""
                INSERT INTO execution (
                    id,project_id,environment_id,test_suite_id,selection_mode,status,requested_by)
                VALUES (?,?,?,?,'SUITE','PENDING','as-028d-service-test')
                """, execution, project, environment, suite);
        return new Fixture(workspace, project, execution);
    }

    private record Fixture(UUID workspaceId, UUID projectId, UUID executionId) { }
}
