package com.automationstudio.engine.selenium.manifest;

import java.util.Arrays;

public enum SeleniumActionType {
    NAVIGATION("navigation"),
    CLICK("click"),
    FILL("fill"),
    SENSITIVE_FILL("sensitive-fill"),
    WAIT_VISIBLE("wait-visible"),
    ASSERT_VISIBLE("assert-visible"),
    ASSERT_TEXT("assert-text"),
    ASSERT_URL("assert-url");

    private final String manifestValue;
    SeleniumActionType(String manifestValue) { this.manifestValue = manifestValue; }
    public String manifestValue() { return manifestValue; }

    public static SeleniumActionType fromManifestValue(String value) {
        return Arrays.stream(values()).filter(type -> type.manifestValue.equals(value)).findFirst()
                .orElseThrow(() -> new SeleniumManifestException(
                        "INVALID_STEP", "Selenium manifest step action is not supported"));
    }
}
