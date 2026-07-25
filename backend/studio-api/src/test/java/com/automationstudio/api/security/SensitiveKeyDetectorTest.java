package com.automationstudio.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SensitiveKeyDetectorTest {

    private final SensitiveKeyDetector detector = new SensitiveKeyDetector();

    @ParameterizedTest
    @ValueSource(strings = {
            "userPassword",
            "secret_value",
            "access-token",
            "API_KEY",
            "PrivateKey",
            "SERVICE_CREDENTIAL"
    })
    void detectsSensitiveKeysAcrossSupportedNamingStyles(String key) {
        assertThat(detector.isSensitive(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"browser", "base_url", "engine-type", "REGION", "secretReference"})
    void acceptsSafeKeys(String key) {
        assertThat(detector.isSensitive(key)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void acceptsNullAndBlankKeys(String key) {
        assertThat(detector.isSensitive(key)).isFalse();
    }
}
