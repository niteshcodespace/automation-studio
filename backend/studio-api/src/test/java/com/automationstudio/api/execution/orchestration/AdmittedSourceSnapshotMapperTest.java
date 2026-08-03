package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.source.SourceConfigurationValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdmittedSourceSnapshotMapperTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private final AdmittedSourceSnapshotMapper mapper =
            new AdmittedSourceSnapshotMapper(new SourceConfigurationValidator());

    @Test
    void mapsOnlyTheImmutableAdmittedSnapshot() {
        var source = mapper.map(Map.of(
                "sourceType", "GIT_HTTPS",
                "repository", "https://example.test/repository.git",
                "revision", REVISION,
                "sourceLocation", "demo/source"));

        assertThat(source.repository()).isEqualTo("https://example.test/repository.git");
        assertThat(source.revision()).isEqualTo(REVISION);
        assertThat(source.sourceLocation()).isEqualTo("demo/source");
    }

    @Test
    void rejectsMissingMalformedAndExpandedSnapshotsWithOneSanitizedFailure() {
        Map<String, Object> expanded = new LinkedHashMap<>();
        expanded.put("sourceType", "GIT_HTTPS");
        expanded.put("repository", "https://user:secret@example.test/repository.git");
        expanded.put("revision", REVISION);
        expanded.put("sourceLocation", null);
        expanded.put("secret", "private-value");

        for (Map<String, Object> snapshot : new Map[] {null, Map.of(), expanded}) {
            assertThatThrownBy(() -> mapper.map(snapshot))
                    .isInstanceOfSatisfying(RunnerPipelineException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("ADMITTED_SOURCE_INVALID");
                        assertThat(failure).hasMessage("Admitted execution source is invalid");
                        assertThat(failure.getCause()).isNull();
                        assertThat(failure.toString())
                                .doesNotContain("private-value", "user:secret");
                    });
        }
    }
}
