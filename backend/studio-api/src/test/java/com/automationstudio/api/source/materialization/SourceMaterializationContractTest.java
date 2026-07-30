package com.automationstudio.api.source.materialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SourceMaterializationContractTest {

    private static final WorkspaceId WORKSPACE_ID =
            new WorkspaceId(UUID.fromString("487138f2-c513-4e85-95d5-276bd6201c52"));
    private static final String REVISION =
            "0123456789abcdef0123456789abcdef01234567";

    @Test
    void requestAndResultAreImmutableSerializableValuesWithoutPaths() throws Exception {
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                "https://example.com/repository.git",
                REVISION,
                "suites/smoke");
        SourceMaterializationRequest request =
                new SourceMaterializationRequest(WORKSPACE_ID, source);
        SourceMaterializationResult result = new SourceMaterializationResult(
                WORKSPACE_ID,
                SourceType.GIT_HTTPS,
                REVISION,
                SourceMaterializationState.MATERIALIZED,
                OffsetDateTime.parse("2026-07-30T10:00:00Z"));

        assertThat(request).isEqualTo(
                new SourceMaterializationRequest(WORKSPACE_ID, source));
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        assertThat(mapper.readValue(
                mapper.writeValueAsString(result),
                SourceMaterializationResult.class)).isEqualTo(result);

        List<Class<?>> contracts = List.of(
                SourceMaterializer.class,
                SourceMaterializationRequest.class,
                SourceMaterializationResult.class,
                SourceMaterializationState.class);
        assertThat(contracts.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(Method::getReturnType))
                .doesNotContain(Path.class, java.io.File.class);
        assertThat(mapper.writeValueAsString(result))
                .doesNotContain("path", "directory", "repository", "credential", "stdout", "stderr");
    }

    @Test
    void rejectsIncompleteContractValues() {
        ExecutionSourceReference source = new ExecutionSourceReference(
                SourceType.GIT_HTTPS,
                "https://example.com/repository.git",
                REVISION,
                null);

        assertThatThrownBy(() -> new SourceMaterializationRequest(null, source))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceMaterializationRequest(WORKSPACE_ID, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceMaterializationResult(
                WORKSPACE_ID,
                SourceType.GIT_HTTPS,
                " ",
                SourceMaterializationState.MATERIALIZED,
                OffsetDateTime.now()))
                .isInstanceOf(SourceMaterializationException.class);
    }
}
