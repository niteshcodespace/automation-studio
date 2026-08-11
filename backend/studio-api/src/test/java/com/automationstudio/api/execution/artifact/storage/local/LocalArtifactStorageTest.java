package com.automationstudio.api.execution.artifact.storage.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.artifact.storage.ArtifactStorageException;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageKey;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArtifactStorageTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void streamsExactBytesComputesIntegrityAndUsesOpaquePhysicalName() throws Exception {
        Path root = temporaryDirectory.resolve("durable");
        var storage = new LocalArtifactStorage(root.toAbsolutePath(), CLOCK);
        ArtifactStorageKey key = ArtifactStorageKey.generate();
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        var stored = storage.store(key, output -> output.write(content), content.length);

        assertThat(stored.storageReference().value()).startsWith("artifact:v1:");
        assertThat(stored.storageReference().toString()).doesNotContain(key.value().toString());
        assertThat(stored.sizeBytes()).isEqualTo(5);
        assertThat(stored.checksumAlgorithm()).isEqualTo("SHA-256");
        assertThat(stored.checksum()).isEqualTo(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(stored.finalizedAt()).isEqualTo(CLOCK.instant());
        assertThat(storage.verify(stored)).isTrue();
        assertThat(Files.list(root).map(path -> path.getFileName().toString()).toList())
                .containsExactly(key.value() + ".artifact")
                .noneMatch(name -> name.contains("logical-name"));
    }

    @Test
    void rejectsOneByteOverLimitAndCleansTemporaryContent() throws Exception {
        Path root = temporaryDirectory.resolve("durable");
        var storage = new LocalArtifactStorage(root.toAbsolutePath(), CLOCK);
        assertThatThrownBy(() -> storage.store(ArtifactStorageKey.generate(),
                output -> output.write(new byte[] {1, 2, 3, 4}), 3))
                .isInstanceOf(ArtifactStorageException.class)
                .hasMessage("Artifact size limit was exceeded");
        assertThat(Files.list(root)).isEmpty();
    }

    @Test
    void writerFailureIsSanitizedAndLeavesNoContent() throws Exception {
        Path root = temporaryDirectory.resolve("durable");
        var storage = new LocalArtifactStorage(root.toAbsolutePath(), CLOCK);
        assertThatThrownBy(() -> storage.store(ArtifactStorageKey.generate(), output -> {
            output.write(1);
            throw new IOException("C:/secret/provider/path");
        }, 10)).isInstanceOf(ArtifactStorageException.class)
                .hasMessage("Artifact storage operation failed safely")
                .hasNoCause();
        assertThat(Files.list(root)).isEmpty();
    }

    @Test
    void collisionNeverReplacesFinalizedBytes() throws Exception {
        Path root = temporaryDirectory.resolve("durable");
        var storage = new LocalArtifactStorage(root.toAbsolutePath(), CLOCK);
        ArtifactStorageKey key = ArtifactStorageKey.generate();
        var first = storage.store(key, output -> output.write(new byte[] {1}), 10);
        assertThatThrownBy(() -> storage.store(key, output -> output.write(new byte[] {2}), 10))
                .isInstanceOf(ArtifactStorageException.class)
                .hasMessage("Artifact storage key collision occurred");
        assertThat(storage.verify(first)).isTrue();
        assertThat(Files.list(root)).hasSize(1);
    }

    @Test
    void verificationDetectsChangedFinalizedBytes() throws Exception {
        Path root = temporaryDirectory.resolve("durable");
        var storage = new LocalArtifactStorage(root.toAbsolutePath(), CLOCK);
        ArtifactStorageKey key = ArtifactStorageKey.generate();
        var stored = storage.store(key, output -> output.write(new byte[] {1, 2}), 10);
        Files.write(root.resolve(key.value() + ".artifact"), new byte[] {2, 1});
        assertThat(storage.verify(stored)).isFalse();
    }

    @Test
    void durableArtifactSurvivesIndependentWorkspaceDeletion() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path root = temporaryDirectory.resolve("durable");
        var storage = new LocalArtifactStorage(root.toAbsolutePath(), CLOCK);
        var stored = storage.store(ArtifactStorageKey.generate(), output -> output.write(7), 10);
        Files.delete(workspace);
        assertThat(storage.verify(stored)).isTrue();
    }

    @Test
    void rejectsRelativeAndSymbolicLinkRootsAndInvalidReferences() throws Exception {
        assertThatThrownBy(() -> new LocalArtifactStorage(Path.of("relative"), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalArtifactStorage(
                temporaryDirectory.toAbsolutePath().getRoot(), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactStorageReference("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactStorageReference("C:/absolute"))
                .isInstanceOf(IllegalArgumentException.class);

        Path actual = Files.createDirectory(temporaryDirectory.resolve("actual"));
        Path link = temporaryDirectory.resolve("link");
        try {
            Files.createSymbolicLink(link, actual);
        } catch (UnsupportedOperationException | IOException failure) {
            Assumptions.abort("Symbolic links are unavailable: " + failure.getMessage());
        }
        assertThatThrownBy(() -> new LocalArtifactStorage(link.toAbsolutePath(), CLOCK))
                .isInstanceOf(ArtifactStorageException.class)
                .hasMessage("Artifact storage boundary is unsafe");
    }
}
