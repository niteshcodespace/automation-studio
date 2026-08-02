package com.automationstudio.api.execution.engine.playwright.manifest;

import java.time.Duration;

public record PlaywrightStep(
        String id,
        PlaywrightActionType action,
        PlaywrightSelector selector,
        String url,
        String value,
        String expected,
        Duration timeout) {

    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_DATA_LENGTH = 4_096;
    public static final long MIN_TIMEOUT_MILLIS = 100;
    public static final long MAX_TIMEOUT_MILLIS = 300_000;

    public PlaywrightStep {
        id = PlaywrightManifestValues.requireText(
                id, MAX_ID_LENGTH, "INVALID_STEP", "Manifest step id is invalid");
        if (action == null) {
            throw invalid("Manifest step action must not be null");
        }
        url = optionalText(url, "Manifest step URL is invalid");
        value = optionalText(value, "Manifest step value is invalid");
        expected = optionalText(expected, "Manifest step expectation is invalid");
        if (timeout != null
                && (timeout.toMillis() < MIN_TIMEOUT_MILLIS
                        || timeout.toMillis() > MAX_TIMEOUT_MILLIS)) {
            throw invalid("Manifest step timeout is outside the supported range");
        }
        validateShape(action, selector, url, value, expected);
    }

    private static void validateShape(
            PlaywrightActionType action,
            PlaywrightSelector selector,
            String url,
            String value,
            String expected) {
        switch (action) {
            case NAVIGATE -> {
                require(url != null, "Navigate step requires url");
                require(selector == null && value == null && expected == null,
                        "Navigate step contains unsupported fields");
            }
            case CLICK -> {
                require(selector != null, "Click step requires selector");
                require(url == null && value == null && expected == null,
                        "Click step contains unsupported fields");
            }
            case FILL -> {
                require(selector != null && value != null,
                        "Fill step requires selector and value");
                require(url == null && expected == null,
                        "Fill step contains unsupported fields");
            }
            case ASSERT_VISIBLE -> {
                require(selector != null, "Visible assertion requires selector");
                require(url == null && value == null && expected == null,
                        "Visible assertion contains unsupported fields");
            }
            case ASSERT_TEXT -> {
                require(selector != null && expected != null,
                        "Text assertion requires selector and expected");
                require(url == null && value == null,
                        "Text assertion contains unsupported fields");
            }
            case ASSERT_URL -> {
                require(expected != null, "URL assertion requires expected");
                require(selector == null && url == null && value == null,
                        "URL assertion contains unsupported fields");
            }
        }
    }

    private static String optionalText(String text, String message) {
        return text == null
                ? null
                : PlaywrightManifestValues.requireText(
                        text, MAX_DATA_LENGTH, "INVALID_STEP", message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private static PlaywrightManifestException invalid(String message) {
        return new PlaywrightManifestException("INVALID_STEP", message);
    }
}
