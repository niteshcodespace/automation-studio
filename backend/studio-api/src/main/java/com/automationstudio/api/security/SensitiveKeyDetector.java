package com.automationstudio.api.security;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SensitiveKeyDetector {

    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
            "password", "secretvalue", "token", "apikey", "privatekey", "credential");

    public boolean isSensitive(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }
}
