package com.automationstudio.api.execution.engine.playwright.action;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class SameOriginNavigationPolicy {
    public static final int MAX_URL_LENGTH = 4_096;
    private final URI baseUri;

    public SameOriginNavigationPolicy(String baseUrl) {
        this.baseUri = parse(baseUrl, "NAVIGATION_BASE_INVALID");
        validateHttpOrigin(baseUri, "NAVIGATION_BASE_INVALID");
    }

    public URI resolve(String target) {
        if (target == null || target.isBlank() || target.length() > MAX_URL_LENGTH
                || target.startsWith("//") || target.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("NAVIGATION_INVALID");
        }
        URI parsed = parse(target, "NAVIGATION_INVALID");
        URI resolved = baseUri.resolve(parsed);
        validateHttpOrigin(resolved, "NAVIGATION_INVALID");
        requireSameOrigin(resolved, "CROSS_ORIGIN_NAVIGATION");
        return resolved.normalize();
    }

    public URI validateFinal(URI finalUri) {
        if (finalUri == null || finalUri.toASCIIString().length() > MAX_URL_LENGTH) {
            throw invalid("REDIRECT_ORIGIN_INVALID");
        }
        validateHttpOrigin(finalUri, "REDIRECT_ORIGIN_INVALID");
        requireSameOrigin(finalUri, "REDIRECT_ORIGIN_INVALID");
        return finalUri.normalize();
    }

    private URI parse(String value, String code) {
        try {
            if (value == null || value.length() > MAX_URL_LENGTH) throw invalid(code);
            return new URI(value);
        } catch (URISyntaxException exception) {
            throw invalid(code);
        }
    }

    private void validateHttpOrigin(URI uri, String code) {
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null || uri.isOpaque()
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw invalid(code);
        }
    }

    private void requireSameOrigin(URI uri, String code) {
        if (!baseUri.getScheme().equalsIgnoreCase(uri.getScheme())
                || !baseUri.getHost().equalsIgnoreCase(uri.getHost())
                || effectivePort(baseUri) != effectivePort(uri)) {
            throw invalid(code);
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT).equals("https") ? 443 : 80;
    }

    private PlaywrightActionException invalid(String code) {
        return new PlaywrightActionException(code, "Navigation is not permitted");
    }
}
