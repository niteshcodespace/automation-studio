package com.automationstudio.engine.sdk;

import java.util.Objects;

/** Exact, extensible, provider-neutral artifact category value. */
public record ArtifactCategory(String value) {

    public static final int MAX_LENGTH = 128;

    public static final ArtifactCategory SCREENSHOT = new ArtifactCategory("SCREENSHOT");
    public static final ArtifactCategory VIDEO = new ArtifactCategory("VIDEO");
    public static final ArtifactCategory TRACE = new ArtifactCategory("TRACE");
    public static final ArtifactCategory LOG = new ArtifactCategory("LOG");
    public static final ArtifactCategory REPORT = new ArtifactCategory("REPORT");
    public static final ArtifactCategory ATTACHMENT = new ArtifactCategory("ATTACHMENT");

    public ArtifactCategory {
        Objects.requireNonNull(value, "Artifact category must not be null");
        if (value.isBlank() || value.length() > MAX_LENGTH
                || !value.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Artifact category is invalid");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
