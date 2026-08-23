package com.automationstudio.engine.selenium.manifest;

public record SeleniumStep(
        String id, SeleniumActionType action, String selector, String url, String value,
        String secretRef, String expected, Integer timeoutMs) {

    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_SELECTOR_LENGTH = 2_048;
    public static final int MAX_VALUE_LENGTH = 4_096;
    public static final int MAX_URL_LENGTH = 2_048;
    public static final int MAX_SECRET_REFERENCE_LENGTH = 128;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 300_000;

    public SeleniumStep {
        id = required(id, MAX_ID_LENGTH);
        if (action == null) invalid();
        selector = optional(selector, MAX_SELECTOR_LENGTH);
        url = optional(url, MAX_URL_LENGTH);
        value = optional(value, MAX_VALUE_LENGTH);
        secretRef = optional(secretRef, MAX_SECRET_REFERENCE_LENGTH);
        expected = optional(expected, MAX_VALUE_LENGTH);
        if (timeoutMs != null && (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS)) invalid();
        switch (action) {
            case NAVIGATION -> require(url != null && selector == null && value == null
                    && secretRef == null && expected == null && timeoutMs == null);
            case CLICK, ASSERT_VISIBLE -> require(selector != null && url == null && value == null
                    && secretRef == null && expected == null && timeoutMs == null);
            case FILL -> require(selector != null && value != null && url == null
                    && secretRef == null && expected == null && timeoutMs == null);
            case SENSITIVE_FILL -> require(selector != null && secretRef != null && url == null
                    && value == null && expected == null && timeoutMs == null);
            case WAIT_VISIBLE -> require(selector != null && url == null && value == null
                    && secretRef == null && expected == null);
            case ASSERT_TEXT -> require(selector != null && expected != null && url == null
                    && value == null && secretRef == null && timeoutMs == null);
            case ASSERT_URL -> require(expected != null && selector == null && url == null
                    && value == null && secretRef == null && timeoutMs == null);
        }
    }

    private static String required(String value, int maximum) {
        String text = optional(value, maximum);
        if (text == null) invalid();
        return text;
    }
    private static String optional(String value, int maximum) {
        if (value == null) return null;
        if (value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0) invalid();
        return value;
    }
    private static void require(boolean valid) { if (!valid) invalid(); }
    private static void invalid() {
        throw new SeleniumManifestException("INVALID_STEP", "Selenium manifest step is invalid");
    }
}
