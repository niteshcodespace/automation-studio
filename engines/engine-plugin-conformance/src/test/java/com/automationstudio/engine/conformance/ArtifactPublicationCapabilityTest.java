package com.automationstudio.engine.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.automationstudio.engine.sdk.ArtifactCategory;
import com.automationstudio.engine.sdk.ArtifactPublication;
import com.automationstudio.engine.sdk.ArtifactPublicationException;
import com.automationstudio.engine.sdk.ArtifactPublisher;
import com.automationstudio.engine.sdk.ArtifactReceipt;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ArtifactPublicationCapabilityTest {

    @Test
    void categoryHasExactExtensibleValueSemantics() {
        assertEquals(new ArtifactCategory("vendor.example.TRACE"),
                new ArtifactCategory("vendor.example.TRACE"));
        assertNotEquals(new ArtifactCategory("trace"), ArtifactCategory.TRACE);
        assertThrows(IllegalArgumentException.class, () -> new ArtifactCategory(" "));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactCategory("BAD\nVALUE"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactCategory("A".repeat(ArtifactCategory.MAX_LENGTH + 1)));
    }

    @Test
    void publicationValidatesAndDefensivelyCopiesMetadata() {
        var mutable = new java.util.HashMap<>(Map.of("step", "login"));
        var publication = publication(mutable, "content");
        mutable.put("secret", "must-not-appear");
        assertEquals(Map.of("step", "login"), publication.metadata());
        assertThrows(UnsupportedOperationException.class,
                () -> publication.metadata().put("other", "value"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactPublication(ArtifactCategory.LOG, "x", "invalid", Map.of(), out -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactPublication(ArtifactCategory.LOG, "bad\nname", "text/plain",
                        Map.of(), out -> {}));
    }

    @Test
    void publisherCorrelatesReceiptComputesIntegrityAndClosesStream() {
        UUID executionId = UUID.randomUUID();
        var publisher = new InMemoryArtifactPublisher(executionId);
        ArtifactReceipt receipt = publisher.publish(publication(Map.of(), "hello"));
        assertEquals(executionId, receipt.executionId());
        assertEquals(5, receipt.sizeBytes());
        assertEquals("SHA-256", receipt.checksumAlgorithm());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                receipt.checksum());
        assertTrue(publisher.lastStreamClosed());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                publisher.observations().getFirst().content());
    }

    @Test
    void receiptContainsNoStorageReferenceAndIsImmutable() {
        var receipt = new ArtifactReceipt(UUID.randomUUID(), UUID.randomUUID(),
                ArtifactCategory.REPORT, "report.txt", "text/plain", 0, "SHA-256",
                "0".repeat(64), Instant.EPOCH);
        assertEquals(9, ArtifactReceipt.class.getRecordComponents().length);
        assertFalse(java.util.Arrays.stream(ArtifactReceipt.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .anyMatch(name -> name.contains("path") || name.contains("storage")
                        || name.contains("uri") || name.contains("credential")));
        assertEquals(Instant.EPOCH, receipt.createdAt());
    }

    @Test
    void unavailableCompatibilityPublisherFailsWithoutPretendingSuccess() {
        UUID executionId = UUID.randomUUID();
        ArtifactPublisher publisher = ArtifactPublisher.unavailable(executionId);
        var failure = assertThrows(ArtifactPublicationException.class,
                () -> publisher.publish(publication(Map.of(), "content")));
        assertEquals("ARTIFACT_PUBLICATION_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains("path"));
        assertEquals(executionId, publisher.executionId());
    }

    @Test
    void failuresAreSanitizedAndFixtureEnforcesBoundedContent() {
        var publisher = new InMemoryArtifactPublisher(UUID.randomUUID(), 4);
        var failure = assertThrows(ArtifactPublicationException.class,
                () -> publisher.publish(publication(Map.of(), "too large")));
        assertEquals("ARTIFACT_PUBLICATION_FAILED", failure.code());
        assertEquals("Artifact publication failed safely", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertTrue(publisher.lastStreamClosed());
        assertTrue(publisher.observations().isEmpty());
    }

    @Test
    void concurrentPublishersRemainExecutionIsolated() throws Exception {
        var first = new InMemoryArtifactPublisher(UUID.randomUUID());
        var second = new InMemoryArtifactPublisher(UUID.randomUUID());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(index -> executor.submit(() -> {
                        InMemoryArtifactPublisher publisher = index % 2 == 0 ? first : second;
                        return publisher.publish(publication(Map.of("index", String.valueOf(index)),
                                "content-" + index));
                    })).toList();
            var artifactIds = new HashSet<UUID>();
            for (var task : tasks) {
                assertTrue(artifactIds.add(task.get().artifactId()));
            }
        }
        assertEquals(10, first.observations().size());
        assertEquals(10, second.observations().size());
        assertTrue(first.observations().stream()
                .allMatch(item -> item.receipt().executionId().equals(first.executionId())));
        assertTrue(second.observations().stream()
                .allMatch(item -> item.receipt().executionId().equals(second.executionId())));
    }

    private static ArtifactPublication publication(Map<String, String> metadata, String content) {
        return new ArtifactPublication(ArtifactCategory.LOG, "engine.log", "text/plain", metadata,
                output -> output.write(content.getBytes(StandardCharsets.UTF_8)));
    }
}
