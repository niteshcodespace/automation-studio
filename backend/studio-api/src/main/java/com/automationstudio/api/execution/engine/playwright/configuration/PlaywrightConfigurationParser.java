package com.automationstudio.api.execution.engine.playwright.configuration;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.security.SensitiveKeyDetector;
import java.time.Duration;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.DEFAULT_ACTION_TIMEOUT;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.DEFAULT_NAVIGATION_TIMEOUT;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.DEFAULT_VIEWPORT_HEIGHT;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.DEFAULT_VIEWPORT_WIDTH;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.MAX_ACTION_TIMEOUT_MILLIS;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.MAX_LOCALE_LENGTH;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.MAX_NAVIGATION_TIMEOUT_MILLIS;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.MAX_VIEWPORT_HEIGHT;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.MAX_VIEWPORT_WIDTH;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.MIN_TIMEOUT_MILLIS;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.MIN_VIEWPORT_HEIGHT;
import static com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration.MIN_VIEWPORT_WIDTH;

@Component
public class PlaywrightConfigurationParser {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "browser",
            "headless",
            "actionTimeoutMs",
            "navigationTimeoutMs",
            "viewportWidth",
            "viewportHeight",
            "locale",
            "navigationPolicy");

    private final SensitiveKeyDetector sensitiveKeyDetector;

    public PlaywrightConfigurationParser(SensitiveKeyDetector sensitiveKeyDetector) {
        this.sensitiveKeyDetector = sensitiveKeyDetector;
    }

    public PlaywrightExecutionConfiguration parse(ExecutionContext context) {
        Objects.requireNonNull(context, "Execution context must not be null");
        Map<String, Object> values = context.suite().configuration();
        rejectUnsupportedKeys(values);

        return new PlaywrightExecutionConfiguration(
                parseBrowser(values.get("browser")),
                parseHeadless(values.get("headless")),
                parseTimeout(
                        values.get("actionTimeoutMs"),
                        DEFAULT_ACTION_TIMEOUT,
                        MAX_ACTION_TIMEOUT_MILLIS),
                parseTimeout(
                        values.get("navigationTimeoutMs"),
                        DEFAULT_NAVIGATION_TIMEOUT,
                        MAX_NAVIGATION_TIMEOUT_MILLIS),
                parseDimension(
                        values.get("viewportWidth"),
                        DEFAULT_VIEWPORT_WIDTH,
                        MIN_VIEWPORT_WIDTH,
                        MAX_VIEWPORT_WIDTH),
                parseDimension(
                        values.get("viewportHeight"),
                        DEFAULT_VIEWPORT_HEIGHT,
                        MIN_VIEWPORT_HEIGHT,
                        MAX_VIEWPORT_HEIGHT),
                parseLocale(values.get("locale")),
                parseNavigationPolicy(values.get("navigationPolicy")));
    }

    private void rejectUnsupportedKeys(Map<String, Object> values) {
        for (String key : values.keySet()) {
            if (key == null
                    || key.isBlank()
                    || sensitiveKeyDetector.isSensitive(key)
                    || !ALLOWED_FIELDS.contains(key)) {
                throw invalid("Playwright configuration contains an unsupported key");
            }
        }
    }

    private static PlaywrightBrowser parseBrowser(Object value) {
        if (value == null || "chromium".equals(value)) {
            return PlaywrightBrowser.CHROMIUM;
        }
        if (!(value instanceof String)) {
            throw invalid("Playwright browser must be a string");
        }
        throw invalid("Playwright browser is not supported");
    }

    private static boolean parseHeadless(Object value) {
        if (value == null || Boolean.TRUE.equals(value)) {
            return true;
        }
        if (Boolean.FALSE.equals(value)) {
            throw invalid("Headed Playwright execution is not supported");
        }
        throw invalid("Playwright headless setting must be boolean");
    }

    private static Duration parseTimeout(Object value, Duration defaultValue, long maximum) {
        if (value == null) {
            return defaultValue;
        }
        long milliseconds = requireIntegralNumber(value, "Playwright timeout must be an integer");
        if (milliseconds < MIN_TIMEOUT_MILLIS || milliseconds > maximum) {
            throw invalid("Playwright timeout is outside the supported range");
        }
        return Duration.ofMillis(milliseconds);
    }

    private static int parseDimension(Object value, int defaultValue, int minimum, int maximum) {
        if (value == null) {
            return defaultValue;
        }
        long dimension =
                requireIntegralNumber(value, "Playwright viewport dimension must be an integer");
        if (dimension < minimum || dimension > maximum) {
            throw invalid("Playwright viewport dimension is outside the supported range");
        }
        return (int) dimension;
    }

    private static long requireIntegralNumber(Object value, String message) {
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)) {
            throw invalid(message);
        }
        return ((Number) value).longValue();
    }

    private static String parseLocale(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalid("Playwright locale must be a string");
        }
        String normalized = text.trim();
        if (normalized.isEmpty()
                || normalized.length() > MAX_LOCALE_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl)
                || !normalized.equals(text)) {
            throw invalid("Playwright locale is invalid");
        }
        try {
            new Locale.Builder().setLanguageTag(normalized);
        } catch (IllformedLocaleException exception) {
            throw invalid("Playwright locale is invalid");
        }
        return normalized;
    }

    private static PlaywrightNavigationPolicy parseNavigationPolicy(Object value) {
        if (value == null || "same-origin".equals(value)) {
            return PlaywrightNavigationPolicy.SAME_ORIGIN;
        }
        if (!(value instanceof String)) {
            throw invalid("Playwright navigation policy must be a string");
        }
        throw invalid("Playwright navigation policy is not supported");
    }

    private static PlaywrightConfigurationException invalid(String message) {
        return new PlaywrightConfigurationException(message);
    }
}
