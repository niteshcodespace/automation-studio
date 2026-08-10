package com.automationstudio.api.execution.artifact.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.artifact.storage.ArtifactStorageKey;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageReference;
import com.automationstudio.api.execution.artifact.storage.StoredArtifact;
import com.automationstudio.engine.sdk.ArtifactCategory;
import com.automationstudio.engine.sdk.ArtifactPublication;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactRegistrationTest {

    @Test
    void reusesSdkBoundsAndDefensivelyCopiesMetadata() {
        var source = new HashMap<>(Map.of("step", "login"));
        var registration = registration(source, "retain:default");
        source.put("secret", "value");
        assertThat(registration.metadata()).containsExactlyEntriesOf(Map.of("step", "login"));
        assertThatThrownBy(() -> registration.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registration(Map.of(
                "x", "v".repeat(ArtifactPublication.MAX_METADATA_VALUE_LENGTH + 1)),
                "retain:default")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidRetentionReference() {
        assertThatThrownBy(() -> registration(Map.of(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registration(Map.of(),
                "r".repeat(ArtifactRegistration.MAX_RETENTION_REFERENCE_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ArtifactRegistration registration(Map<String, String> metadata, String retention) {
        UUID id = UUID.randomUUID();
        return new ArtifactRegistration(id, ArtifactCategory.LOG, "artifact.log", "text/plain",
                metadata, new StoredArtifact(ArtifactStorageReference.from(new ArtifactStorageKey(id)),
                0, "SHA-256", "0".repeat(64), Instant.EPOCH), retention);
    }
}
