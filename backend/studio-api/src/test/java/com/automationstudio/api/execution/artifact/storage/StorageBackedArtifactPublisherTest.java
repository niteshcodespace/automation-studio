package com.automationstudio.api.execution.artifact.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.artifact.storage.local.LocalArtifactStorage;
import com.automationstudio.api.execution.artifact.metadata.ArtifactMetadataException;
import com.automationstudio.api.execution.artifact.metadata.ArtifactMetadataService;
import com.automationstudio.engine.sdk.ArtifactCategory;
import com.automationstudio.engine.sdk.ArtifactPublication;
import com.automationstudio.engine.sdk.ArtifactPublicationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageBackedArtifactPublisherTest {

    @TempDir Path temporaryDirectory;

    @Test
    void receiptMatchesVerifiedStorageOutcome() {
        UUID executionId = UUID.randomUUID();
        var storage = storage("durable");
        var publisher = publisher(executionId, storage, limits(5, 20, 4, 2));
        var receipt = publisher.publish(publication("hello"));
        assertThat(receipt.executionId()).isEqualTo(executionId);
        assertThat(receipt.sizeBytes()).isEqualTo(5);
        assertThat(receipt.checksum()).isEqualTo(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(publisher.finalizedArtifacts()).isEqualTo(1);
        assertThat(publisher.finalizedBytes()).isEqualTo(5);
    }

    @Test
    void enforcesPerArtifactCumulativeAndCountLimitsWithoutInflatingFailures() {
        var publisher = publisher(UUID.randomUUID(), storage("limits"), limits(4, 6, 2, 2));
        publisher.publish(publication("1234"));
        assertThatThrownBy(() -> publisher.publish(publication("123")))
                .isInstanceOf(ArtifactPublicationException.class);
        assertThat(publisher.finalizedArtifacts()).isEqualTo(1);
        assertThat(publisher.finalizedBytes()).isEqualTo(4);
        publisher.publish(publication("12"));
        assertThatThrownBy(() -> publisher.publish(publication("")))
                .isInstanceOf(ArtifactPublicationException.class)
                .hasMessage("Artifact execution limit was exceeded");
        assertThat(publisher.finalizedArtifacts()).isEqualTo(2);
        assertThat(publisher.finalizedBytes()).isEqualTo(6);
    }

    @Test
    void failedWriterDoesNotConsumeFinalizedQuota() {
        var publisher = publisher(UUID.randomUUID(), storage("failure"), limits(10, 10, 1, 1));
        var failed = new ArtifactPublication(ArtifactCategory.LOG, "failed.log", "text/plain",
                Map.of(), output -> { throw new java.io.IOException("provider detail"); });
        assertThatThrownBy(() -> publisher.publish(failed))
                .isInstanceOf(ArtifactPublicationException.class)
                .hasMessage("Artifact publication failed safely");
        assertThat(publisher.finalizedArtifacts()).isZero();
        assertThat(publisher.finalizedBytes()).isZero();
        publisher.publish(publication("ok"));
        assertThat(publisher.finalizedArtifacts()).isEqualTo(1);
    }

    @Test
    void enforcesConcurrentOpenLimitDeterministically() throws Exception {
        var publisher = publisher(UUID.randomUUID(), storage("concurrent"), limits(20, 40, 4, 1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocking = new ArtifactPublication(ArtifactCategory.LOG, "blocking.log", "text/plain",
                Map.of(), output -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new java.io.IOException("coordination failed");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("coordination interrupted");
                    }
                    output.write(1);
                });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> publisher.publish(blocking));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> publisher.publish(publication("second")))
                    .isInstanceOf(ArtifactPublicationException.class)
                    .hasMessage("Artifact execution limit was exceeded");
            release.countDown();
            assertThat(first.get().sizeBytes()).isEqualTo(1);
        }
    }

    @Test
    void separateExecutionPublishersHaveIndependentAccounting() {
        var storage = storage("isolated");
        var first = publisher(UUID.randomUUID(), storage, limits(2, 2, 1, 1));
        var second = publisher(UUID.randomUUID(), storage, limits(2, 2, 1, 1));
        assertThat(first.publish(publication("12")).executionId()).isEqualTo(first.executionId());
        assertThat(second.publish(publication("34")).executionId()).isEqualTo(second.executionId());
        assertThat(first.finalizedBytes()).isEqualTo(2);
        assertThat(second.finalizedBytes()).isEqualTo(2);
    }

    @Test
    void productionReceiptRequiresMetadataRegistration() {
        UUID executionId = UUID.randomUUID();
        ArtifactMetadataService metadata = mock(ArtifactMetadataService.class);
        var publisher = new StorageBackedArtifactPublisher(
                executionId, storage("metadata"), limits(10, 10, 1, 1),
                UUID.randomUUID(), UUID.randomUUID(), metadata, "retain:default");

        var receipt = publisher.publish(publication("proof"));
        publisher.complete();

        assertThat(receipt.executionId()).isEqualTo(executionId);
        verify(metadata).register(any(), any(), org.mockito.Mockito.eq(executionId), any());
        assertThatThrownBy(() -> publisher.publish(publication("late")))
                .isInstanceOf(ArtifactPublicationException.class)
                .hasMessage("Artifact publication is unavailable for this execution");
    }

    @Test
    void metadataFailureIsPrimaryAndMakesInvocationFailClosed() {
        ArtifactMetadataService metadata = mock(ArtifactMetadataService.class);
        when(metadata.register(any(), any(), any(), any())).thenThrow(
                new ArtifactMetadataException("ARTIFACT_METADATA_PERSISTENCE_FAILED",
                        "Artifact metadata registration failed safely"));
        var publisher = new StorageBackedArtifactPublisher(
                UUID.randomUUID(), storage("metadata-failure"), limits(10, 10, 1, 1),
                UUID.randomUUID(), UUID.randomUUID(), metadata, "retain:default");

        assertThatThrownBy(() -> publisher.publish(publication("proof")))
                .isInstanceOf(ArtifactPublicationException.class)
                .hasMessage("Artifact publication failed safely");
        assertThat(publisher.finalizedArtifacts()).isZero();
        assertThatThrownBy(publisher::complete)
                .isInstanceOf(ArtifactPublicationException.class);
    }

    private LocalArtifactStorage storage(String name) {
        return new LocalArtifactStorage(
                temporaryDirectory.resolve(name).toAbsolutePath(), Clock.systemUTC());
    }

    private static StorageBackedArtifactPublisher publisher(
            UUID executionId, ArtifactStorage storage, ArtifactStorageLimits limits) {
        return new StorageBackedArtifactPublisher(executionId, storage, limits);
    }

    private static ArtifactStorageLimits limits(long item, long total, int count, int concurrent) {
        return new ArtifactStorageLimits("C:/unused-test-root", item, total, count, concurrent);
    }

    private static ArtifactPublication publication(String content) {
        return new ArtifactPublication(ArtifactCategory.LOG, "engine.log", "text/plain", Map.of(),
                output -> output.write(content.getBytes(StandardCharsets.UTF_8)));
    }
}
