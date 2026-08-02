package com.automationstudio.api.execution.engine.playwright.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.security.SensitiveKeyDetector;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PlaywrightConfigurationParserTest {

    private final PlaywrightConfigurationParser parser =
            new PlaywrightConfigurationParser(new SensitiveKeyDetector());

    @Test
    void parsesExplicitConfiguration() {
        PlaywrightExecutionConfiguration parsed = parser.parse(context(Map.of(
                "browser", "chromium",
                "headless", true,
                "actionTimeoutMs", 5_000,
                "navigationTimeoutMs", 45_000L,
                "viewportWidth", 1920,
                "viewportHeight", 1080,
                "locale", "en-US",
                "navigationPolicy", "same-origin")));

        assertThat(parsed.browser()).isEqualTo(PlaywrightBrowser.CHROMIUM);
        assertThat(parsed.headless()).isTrue();
        assertThat(parsed.actionTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(parsed.navigationTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(parsed.viewportWidth()).isEqualTo(1920);
        assertThat(parsed.viewportHeight()).isEqualTo(1080);
        assertThat(parsed.locale()).isEqualTo("en-US");
        assertThat(parsed.navigationPolicy())
                .isEqualTo(PlaywrightNavigationPolicy.SAME_ORIGIN);
    }

    @Test
    void appliesDeterministicSecureDefaults() {
        PlaywrightExecutionConfiguration parsed = parser.parse(context(Map.of()));

        assertThat(parsed.browser()).isEqualTo(PlaywrightBrowser.CHROMIUM);
        assertThat(parsed.headless()).isTrue();
        assertThat(parsed.actionTimeout())
                .isEqualTo(PlaywrightExecutionConfiguration.DEFAULT_ACTION_TIMEOUT);
        assertThat(parsed.navigationTimeout())
                .isEqualTo(PlaywrightExecutionConfiguration.DEFAULT_NAVIGATION_TIMEOUT);
        assertThat(parsed.viewportWidth())
                .isEqualTo(PlaywrightExecutionConfiguration.DEFAULT_VIEWPORT_WIDTH);
        assertThat(parsed.viewportHeight())
                .isEqualTo(PlaywrightExecutionConfiguration.DEFAULT_VIEWPORT_HEIGHT);
        assertThat(parsed.locale()).isNull();
        assertThat(parsed.navigationPolicy())
                .isEqualTo(PlaywrightNavigationPolicy.SAME_ORIGIN);
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurations")
    void rejectsMalformedUnsupportedAndOutOfBoundsConfiguration(
            Map<String, Object> configuration) {
        assertThatThrownBy(() -> parser.parse(context(configuration)))
                .isInstanceOf(PlaywrightConfigurationException.class)
                .hasMessageNotContaining("secret-value");
    }

    private static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                arguments("browser", "firefox"),
                arguments("browser", true),
                arguments("headless", false),
                arguments("headless", "true"),
                arguments("actionTimeoutMs", 99),
                arguments("actionTimeoutMs", 120_001),
                arguments("actionTimeoutMs", 100.0),
                arguments("navigationTimeoutMs", 99),
                arguments("navigationTimeoutMs", 300_001L),
                arguments("navigationTimeoutMs", "30000"),
                arguments("viewportWidth", 319),
                arguments("viewportWidth", 3841),
                arguments("viewportWidth", 1280.0),
                arguments("viewportHeight", 199),
                arguments("viewportHeight", 2161),
                arguments("locale", ""),
                arguments("locale", " en-US"),
                arguments("locale", "not_a_locale"),
                arguments("locale", 1),
                arguments("navigationPolicy", "allow-all"),
                arguments("navigationPolicy", true),
                arguments("unknown", "secret-value"),
                arguments("apiToken", "secret-value"));
    }

    private static Arguments arguments(String key, Object value) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(key, value);
        return Arguments.of(configuration);
    }

    private static ExecutionContext context(Map<String, Object> configuration) {
        ExecutionContext context = mock(ExecutionContext.class);
        ExecutionSuiteSnapshot suite = mock(ExecutionSuiteSnapshot.class);
        when(context.suite()).thenReturn(suite);
        when(suite.configuration()).thenReturn(configuration);
        return context;
    }
}
