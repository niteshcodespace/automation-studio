package com.automationstudio.engine.sdk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Engine-proposed artifact description and streaming content producer. */
public record ArtifactPublication(
        ArtifactCategory category,
        String logicalName,
        String mediaType,
        Map<String, String> metadata,
        ContentWriter contentWriter) {

    public static final int MAX_LOGICAL_NAME_LENGTH = 255;
    public static final int MAX_MEDIA_TYPE_LENGTH = 255;
    public static final int MAX_METADATA_ENTRIES = 32;
    public static final int MAX_METADATA_KEY_LENGTH = 128;
    public static final int MAX_METADATA_VALUE_LENGTH = 1024;
    public static final int MAX_METADATA_ENCODED_LENGTH = 8192;

    public ArtifactPublication {
        category = Objects.requireNonNull(category, "Artifact category must not be null");
        logicalName = requireBoundedText(
                logicalName, MAX_LOGICAL_NAME_LENGTH, "Artifact logical name");
        mediaType = requireMediaType(mediaType);
        metadata = immutableMetadata(metadata);
        contentWriter = Objects.requireNonNull(contentWriter, "Artifact content writer must not be null");
    }

    private static String requireMediaType(String value) {
        String mediaType = requireBoundedText(value, MAX_MEDIA_TYPE_LENGTH, "Artifact media type");
        int separator = mediaType.indexOf('/');
        if (separator <= 0 || separator == mediaType.length() - 1
                || mediaType.indexOf('/', separator + 1) >= 0
                || mediaType.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                || Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("Artifact media type is invalid");
        }
        return mediaType;
    }

    private static Map<String, String> immutableMetadata(Map<String, String> source) {
        Objects.requireNonNull(source, "Artifact metadata must not be null");
        if (source.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("Artifact metadata has too many entries");
        }
        int encodedLength = 0;
        for (var entry : source.entrySet()) {
            String key = requireBoundedText(
                    entry.getKey(), MAX_METADATA_KEY_LENGTH, "Artifact metadata key");
            String value = Objects.requireNonNull(
                    entry.getValue(), "Artifact metadata value must not be null");
            if (value.length() > MAX_METADATA_VALUE_LENGTH || containsControl(value)) {
                throw new IllegalArgumentException("Artifact metadata value is invalid");
            }
            encodedLength = Math.addExact(encodedLength,
                    key.getBytes(StandardCharsets.UTF_8).length
                            + value.getBytes(StandardCharsets.UTF_8).length);
        }
        if (encodedLength > MAX_METADATA_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Artifact metadata is too large");
        }
        return Map.copyOf(source);
    }

    private static String requireBoundedText(String value, int maximumLength, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > maximumLength || containsControl(value)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    /**
     * Writes content to a publisher-owned stream. The stream is valid only for this callback and
     * must not be retained or closed by the writer. Resources acquired by the writer remain its
     * responsibility.
     */
    @FunctionalInterface
    public interface ContentWriter {
        void writeTo(OutputStream output) throws IOException;
    }
}
