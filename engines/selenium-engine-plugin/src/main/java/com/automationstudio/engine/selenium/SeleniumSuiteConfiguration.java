package com.automationstudio.engine.selenium;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record SeleniumSuiteConfiguration(
        String browser, boolean headless, int actionTimeoutMs, int pageLoadTimeoutMs,
        int viewportWidth, int viewportHeight, String navigationPolicy) {

    private static final Set<String> FIELDS = Set.of("browser", "headless", "actionTimeoutMs",
            "pageLoadTimeoutMs", "viewportWidth", "viewportHeight", "navigationPolicy");
    private static final Pattern SENSITIVE = Pattern.compile(
            ".*(secret|password|token|credential|authorization|cookie|api[-_]?key).*",
            Pattern.CASE_INSENSITIVE);

    public static SeleniumSuiteConfiguration parse(Map<String, Object> values) {
        if (values == null || !FIELDS.containsAll(values.keySet())
                || values.keySet().stream().anyMatch(key -> SENSITIVE.matcher(key).matches())) {
            throw failure();
        }
        return new SeleniumSuiteConfiguration(
                exactString(values, "browser", "chrome"),
                booleanValue(values, "headless", true),
                integer(values, "actionTimeoutMs", 30_000, 100, 120_000),
                integer(values, "pageLoadTimeoutMs", 30_000, 100, 300_000),
                integer(values, "viewportWidth", 1_280, 320, 3_840),
                integer(values, "viewportHeight", 720, 200, 2_160),
                exactString(values, "navigationPolicy", "same-origin"));
    }

    private static String exactString(Map<String, Object> values, String key, String required) {
        Object value = values.getOrDefault(key, required);
        if (!(value instanceof String text) || !required.equals(text)) throw failure();
        return text;
    }

    private static boolean booleanValue(Map<String, Object> values, String key, boolean required) {
        Object value = values.getOrDefault(key, required);
        if (!(value instanceof Boolean bool) || bool != required) throw failure();
        return bool;
    }

    private static int integer(Map<String, Object> values, String key,
            int fallback, int minimum, int maximum) {
        Object value = values.get(key);
        if (value == null) return fallback;
        BigInteger integer;
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            integer = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger bigInteger) {
            integer = bigInteger;
        } else {
            throw failure();
        }
        if (integer.compareTo(BigInteger.valueOf(minimum)) < 0
                || integer.compareTo(BigInteger.valueOf(maximum)) > 0) throw failure();
        return integer.intValue();
    }

    private static SeleniumEngineException failure() {
        return new SeleniumEngineException(
                "INVALID_ENGINE_CONFIGURATION", "Selenium suite configuration is invalid");
    }
}
