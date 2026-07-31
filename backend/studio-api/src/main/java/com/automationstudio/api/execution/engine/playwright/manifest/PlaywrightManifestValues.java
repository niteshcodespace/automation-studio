package com.automationstudio.api.execution.engine.playwright.manifest;

final class PlaywrightManifestValues {

    private PlaywrightManifestValues() {}

    static String requireText(
            String value, int maximumLength, String code, String message) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || !value.equals(value.trim())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new PlaywrightManifestException(code, message);
        }
        return value;
    }
}
