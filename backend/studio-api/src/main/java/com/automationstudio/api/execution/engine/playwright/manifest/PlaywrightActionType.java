package com.automationstudio.api.execution.engine.playwright.manifest;

public enum PlaywrightActionType {
    NAVIGATE("navigate"),
    CLICK("click"),
    FILL("fill"),
    ASSERT_VISIBLE("assert-visible"),
    ASSERT_TEXT("assert-text"),
    ASSERT_URL("assert-url");

    private final String manifestValue;

    PlaywrightActionType(String manifestValue) {
        this.manifestValue = manifestValue;
    }

    public String manifestValue() {
        return manifestValue;
    }

    static PlaywrightActionType fromManifestValue(String value) {
        for (PlaywrightActionType type : values()) {
            if (type.manifestValue.equals(value)) {
                return type;
            }
        }
        throw new PlaywrightManifestException(
                "INVALID_STEP", "Manifest step action is not supported");
    }
}
