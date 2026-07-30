package com.automationstudio.api.execution.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ExecutionArtifactReferenceTest {

    @Test
    void retainsOnlyReferenceMetadata() {
        ExecutionArtifactReference reference = new ExecutionArtifactReference(
                URI.create("artifact://runner/executions/result.xml"),
                "runner-spool",
                "sha256:abc",
                "gzip");

        assertThat(reference.uri()).hasScheme("artifact");
        assertThat(reference.storageProvider()).isEqualTo("runner-spool");
        assertThat(reference.checksum()).isEqualTo("sha256:abc");
        assertThat(reference.compression()).isEqualTo("gzip");
    }

    @Test
    void rejectsMissingProviderAndBlankOptionalValues() {
        assertThatThrownBy(() -> new ExecutionArtifactReference(
                URI.create("artifact://runner/item"), " ", null, null))
                .isInstanceOf(ExecutionEvidenceException.class);
        assertThatThrownBy(() -> new ExecutionArtifactReference(
                URI.create("artifact://runner/item"), "runner", " ", null))
                .isInstanceOf(ExecutionEvidenceException.class);
    }
}
