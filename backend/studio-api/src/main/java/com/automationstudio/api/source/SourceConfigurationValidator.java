package com.automationstudio.api.source;

import com.automationstudio.api.exception.InvalidRequestException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SourceConfigurationValidator {

    public static final int MAX_REPOSITORY_LENGTH = 1000;
    public static final int MAX_SOURCE_LOCATION_LENGTH = 500;
    public static final int MAX_SOURCE_LOCATION_DEPTH = 20;
    private static final String EXACT_SHA1_PATTERN = "[0-9a-fA-F]{40}";
    private static final String WINDOWS_DRIVE_PATTERN = "^[A-Za-z]:.*";

    public ExecutionSourceReference validate(
            SourceType sourceType,
            String repository,
            String revision,
            String sourceLocation) {
        if (sourceType == null) {
            throw invalid("Source type must not be null");
        }
        if (sourceType != SourceType.GIT_HTTPS) {
            throw invalid("Source type is not supported");
        }
        return new ExecutionSourceReference(
                sourceType,
                normalizeRepository(repository),
                normalizeRevision(revision),
                normalizeSourceLocation(sourceLocation));
    }

    public String normalizeRepository(String value) {
        String repository = requireExactText(
                value, "Repository identity", MAX_REPOSITORY_LENGTH);
        if (containsControlCharacter(repository)) {
            throw invalid("Repository identity is invalid");
        }
        URI uri;
        try {
            uri = new URI(repository);
        } catch (URISyntaxException exception) {
            throw invalid("Repository identity is invalid");
        }
        if (!uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw invalid("Repository identity must be a credential-free HTTPS URI");
        }
        if (uri.getPort() < -1 || uri.getRawPath() == null || uri.getRawPath().isBlank()) {
            throw invalid("Repository identity is invalid");
        }
        try {
            return new URI(
                    "https",
                    null,
                    uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort(),
                    uri.getRawPath(),
                    null,
                    null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw invalid("Repository identity is invalid");
        }
    }

    public String normalizeRevision(String value) {
        String revision = requireExactText(value, "Source revision", 40);
        if (!revision.matches(EXACT_SHA1_PATTERN)) {
            throw invalid("Source revision must be an exact 40-character Git commit SHA");
        }
        return revision.toLowerCase(Locale.ROOT);
    }

    public String normalizeSourceLocation(String value) {
        if (value == null) {
            return null;
        }
        String location = requireExactText(
                value, "Source location", MAX_SOURCE_LOCATION_LENGTH);
        if (containsControlCharacter(location)
                || location.startsWith("/")
                || location.startsWith("\\")
                || location.startsWith("~")
                || location.matches(WINDOWS_DRIVE_PATTERN)
                || location.contains("\\")
                || location.contains("//")) {
            throw invalid("Source location must be a portable repository-relative path");
        }
        String[] segments = location.split("/", -1);
        if (segments.length > MAX_SOURCE_LOCATION_DEPTH) {
            throw invalid("Source location exceeds the maximum depth");
        }
        List<String> normalized = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("Source location contains an invalid path segment");
            }
            normalized.add(segment);
        }
        return String.join("/", normalized);
    }

    private static String requireExactText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " must not be blank");
        }
        if (!value.equals(value.trim())) {
            throw invalid(name + " must not contain surrounding whitespace");
        }
        if (value.length() > maximumLength) {
            throw invalid(name + " exceeds the maximum length");
        }
        return value;
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static InvalidRequestException invalid(String message) {
        return new InvalidRequestException(message);
    }
}
