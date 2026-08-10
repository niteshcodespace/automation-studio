package com.automationstudio.engine.conformance;

import com.automationstudio.engine.sdk.ArtifactPublication;
import com.automationstudio.engine.sdk.ArtifactPublicationException;
import com.automationstudio.engine.sdk.ArtifactPublisher;
import com.automationstudio.engine.sdk.ArtifactReceipt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** SDK-only test fixture. It observes publications in memory and makes no durability claim. */
public final class InMemoryArtifactPublisher implements ArtifactPublisher {

    public static final int DEFAULT_MAX_BYTES = 1_048_576;

    private final UUID executionId;
    private final int maximumBytes;
    private final List<PublicationObservation> observations = new CopyOnWriteArrayList<>();
    private volatile boolean lastStreamClosed;

    public InMemoryArtifactPublisher(UUID executionId) {
        this(executionId, DEFAULT_MAX_BYTES);
    }

    public InMemoryArtifactPublisher(UUID executionId, int maximumBytes) {
        this.executionId = Objects.requireNonNull(executionId, "Execution ID must not be null");
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("Maximum artifact bytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    @Override
    public UUID executionId() {
        return executionId;
    }

    @Override
    public ArtifactReceipt publish(ArtifactPublication publication) {
        ArtifactPublication candidate = Objects.requireNonNull(
                publication, "Artifact publication must not be null");
        var output = new BoundedOutput(maximumBytes);
        try (output) {
            candidate.contentWriter().writeTo(output);
        } catch (Exception failure) {
            throw new ArtifactPublicationException(
                    "ARTIFACT_PUBLICATION_FAILED", "Artifact publication failed safely");
        } finally {
            lastStreamClosed = output.closed();
        }

        byte[] content = output.toByteArray();
        String checksum = HexFormat.of().formatHex(sha256().digest(content));
        var receipt = new ArtifactReceipt(UUID.randomUUID(), executionId, candidate.category(),
                candidate.logicalName(), candidate.mediaType(), content.length, "SHA-256", checksum,
                Instant.now());
        observations.add(new PublicationObservation(receipt, candidate.metadata(), content));
        return receipt;
    }

    public List<PublicationObservation> observations() {
        return List.copyOf(observations);
    }

    public boolean lastStreamClosed() {
        return lastStreamClosed;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public record PublicationObservation(
            ArtifactReceipt receipt, java.util.Map<String, String> metadata, byte[] content) {
        public PublicationObservation {
            receipt = Objects.requireNonNull(receipt, "Artifact receipt must not be null");
            metadata = java.util.Map.copyOf(metadata);
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        private final int maximumBytes;
        private boolean closed;

        private BoundedOutput(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] value, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, value.length);
            requireCapacity(length);
            super.write(value, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private void requireCapacity(int additionalBytes) {
            if (additionalBytes > maximumBytes - size()) {
                throw new ArtifactPublicationException(
                        "ARTIFACT_LIMIT_EXCEEDED", "Artifact content exceeds the fixture limit");
            }
        }

        private boolean closed() {
            return closed;
        }
    }
}
