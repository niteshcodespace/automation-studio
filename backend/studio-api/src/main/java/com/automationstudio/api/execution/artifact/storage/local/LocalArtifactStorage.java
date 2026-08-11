package com.automationstudio.api.execution.artifact.storage.local;

import com.automationstudio.api.execution.artifact.storage.ArtifactStorage;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageContent;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageException;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageKey;
import com.automationstudio.api.execution.artifact.storage.ArtifactStorageReference;
import com.automationstudio.api.execution.artifact.storage.StoredArtifact;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

public final class LocalArtifactStorage implements ArtifactStorage {

    private final Path root;
    private final Clock clock;

    public LocalArtifactStorage(Path root, Clock clock) {
        this.root = requireSecureRoot(root);
        this.clock = Objects.requireNonNull(clock, "Artifact storage clock must not be null");
    }

    @Override
    public StoredArtifact store(
            ArtifactStorageKey key, ArtifactStorageContent content, long maximumBytes) {
        Objects.requireNonNull(key, "Artifact storage key must not be null");
        Objects.requireNonNull(content, "Artifact storage content must not be null");
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("Maximum artifact bytes must be positive");
        }
        requireRootUnchanged();
        Path temporary = resolve(".pending-" + key.value());
        Path target = resolve(key.value() + ".artifact");
        boolean finalized = false;
        try {
            MessageDigest digest = sha256();
            var counter = new BoundedOutputStream(Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), maximumBytes);
            try (var output = new DigestOutputStream(counter, digest)) {
                content.writeTo(output);
            }
            moveWithoutReplacement(temporary, target);
            finalized = true;
            return new StoredArtifact(ArtifactStorageReference.from(key), counter.size(), "SHA-256",
                    HexFormat.of().formatHex(digest.digest()), clock.instant());
        } catch (Exception failure) {
            if (finalized) {
                deleteBestEffort(target);
            }
            throw sanitized(failure);
        } finally {
            deleteBestEffort(temporary);
        }
    }

    @Override
    public boolean verify(StoredArtifact artifact) {
        Objects.requireNonNull(artifact, "Stored artifact must not be null");
        requireRootUnchanged();
        Path target = resolve(artifact.storageReference().opaqueId() + ".artifact");
        if (Files.isSymbolicLink(target)) {
            throw unsafeStorage();
        }
        try (InputStream input = new DigestInputStream(Files.newInputStream(target,
                StandardOpenOption.READ), sha256())) {
            long size = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                size += read;
            }
            MessageDigest digest = ((DigestInputStream) input).getMessageDigest();
            return size == artifact.sizeBytes()
                    && HexFormat.of().formatHex(digest.digest()).equals(artifact.checksum());
        } catch (IOException failure) {
            throw sanitized(failure);
        }
    }

    @Override
    public void delete(ArtifactStorageReference reference) {
        Objects.requireNonNull(reference, "Artifact storage reference must not be null");
        requireRootUnchanged();
        Path target = resolve(reference.opaqueId() + ".artifact");
        if (Files.isSymbolicLink(target)) {
            throw unsafeStorage();
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException failure) {
            throw sanitized(failure);
        }
    }

    private static Path requireSecureRoot(Path candidate) {
        Objects.requireNonNull(candidate, "Artifact storage root must not be null");
        if (!candidate.isAbsolute()) {
            throw new IllegalArgumentException("Artifact storage root must be absolute");
        }
        Path normalized = candidate.normalize();
        if (normalized.getParent() == null) {
            throw new IllegalArgumentException("Artifact storage root must not be a filesystem root");
        }
        try {
            requireNoSymbolicLinkSegment(normalized);
            Files.createDirectories(normalized);
            requireNoSymbolicLinkSegment(normalized);
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw unsafeStorage();
            }
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw sanitized(failure);
        }
    }

    private static void requireNoSymbolicLinkSegment(Path path) {
        Path current = path.getRoot();
        for (Path segment : path) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw unsafeStorage();
            }
        }
    }

    private void requireRootUnchanged() {
        try {
            requireNoSymbolicLinkSegment(root);
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !root.equals(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw unsafeStorage();
            }
        } catch (IOException failure) {
            throw sanitized(failure);
        }
    }

    private Path resolve(String filename) {
        Path resolved = root.resolve(filename).normalize();
        if (!resolved.getParent().equals(root)) {
            throw unsafeStorage();
        }
        return resolved;
    }

    private static void moveWithoutReplacement(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArtifactStorageException(
                    "ARTIFACT_STORAGE_COLLISION", "Artifact storage key collision occurred");
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static ArtifactStorageException sanitized(Exception failure) {
        if (failure instanceof ArtifactStorageException storageFailure) {
            return storageFailure;
        }
        return new ArtifactStorageException(
                "ARTIFACT_STORAGE_FAILED", "Artifact storage operation failed safely");
    }

    private static ArtifactStorageException unsafeStorage() {
        return new ArtifactStorageException(
                "ARTIFACT_STORAGE_UNSAFE", "Artifact storage boundary is unsafe");
    }

    private static void deleteBestEffort(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the primary failure; operational orphan handling belongs to AS-080.
        }
    }

    private static final class BoundedOutputStream extends FilterOutputStream {
        private final long maximumBytes;
        private long size;

        private BoundedOutputStream(OutputStream output, long maximumBytes) {
            super(output);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            out.write(value);
            size++;
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, value.length);
            requireCapacity(length);
            out.write(value, offset, length);
            size += length;
        }

        private void requireCapacity(int additionalBytes) {
            if (additionalBytes > maximumBytes - size) {
                throw new ArtifactStorageException(
                        "ARTIFACT_SIZE_LIMIT_EXCEEDED", "Artifact size limit was exceeded");
            }
        }

        private long size() {
            return size;
        }
    }
}
