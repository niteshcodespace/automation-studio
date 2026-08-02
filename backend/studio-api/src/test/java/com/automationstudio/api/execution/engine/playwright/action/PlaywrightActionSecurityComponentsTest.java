package com.automationstudio.api.execution.engine.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightSelector;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightActionRuntime;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaywrightActionSecurityComponentsTest {
    private final CssSelectorResolver selectors = new CssSelectorResolver();

    @Test
    void resolvesCssWithoutSemanticRewriting() {
        for (String value : new String[] {
                "[data-test='save'] > button",
                "[data-value=\"a>>b\"]",
                "[data-value='internal:role=button']",
                "[data-value=\"a\\\" >> b\"]",
                "div::before",
                "button:hover",
                "input[type=\"text\"]",
                "a[href*=\"example\"]",
                "* > div"
        }) {
            assertThat(selectors.resolve(new PlaywrightSelector(value))).isEqualTo(value);
        }
    }

    @Test
    void rejectsInvalidAndUnsupportedSelectorsWithoutLeakingContent() {
        for (String value : new String[] {
                "", " xpath=//secret", "xpath=//secret", "//div", "..", "../div",
                "text=secret", "role=button", "id=secret", "css=div", "div >> text=secret",
                "*css=div", "*xpath=//div", "**css=div", "internal:role=button",
                "internal:text=\"Submit\"", "CsS=div", "\u00a0css=div", "css=div\u00a0",
                "div[", "a\nsecret"
        }) {
            PlaywrightSelector selector = mock(PlaywrightSelector.class);
            when(selector.value()).thenReturn(value);
            assertThatThrownBy(() -> selectors.resolve(selector))
                    .isInstanceOfSatisfying(PlaywrightActionException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("SELECTOR_INVALID");
                        assertThat(failure.getMessage()).doesNotContain("secret");
                    });
        }
        PlaywrightSelector oversized = mock(PlaywrightSelector.class);
        when(oversized.value()).thenReturn("a".repeat(PlaywrightSelector.MAX_LENGTH + 1));
        assertThatThrownBy(() -> selectors.resolve(oversized))
                .isInstanceOf(PlaywrightActionException.class);
    }

    @Test
    void rejectedImplicitXpathNeverReachesRuntimeFacade() {
        for (String value : new String[] {
                "//div", "../div", "*css=div", "*xpath=//div",
                "internal:role=button", "internal:text=\"Submit\"", "div >> text=example"
        }) {
            PlaywrightActionRuntime runtime = mock(PlaywrightActionRuntime.class);
            PlaywrightActionExecutionContext context = mock(PlaywrightActionExecutionContext.class);
            when(context.selectorResolver()).thenReturn(selectors);
            when(context.runtime()).thenReturn(runtime);
            PlaywrightStep step = new PlaywrightStep(
                    "step", PlaywrightActionType.CLICK, new PlaywrightSelector(value),
                    null, null, null, null);

            assertThatThrownBy(() -> new ClickActionExecutor().execute(step, context))
                    .isInstanceOfSatisfying(PlaywrightActionException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("SELECTOR_INVALID");
                        assertThat(failure.getMessage()).isEqualTo("Manifest selector is invalid");
                        assertThat(failure.getMessage()).doesNotContain(value);
                    });
            verifyNoInteractions(runtime);
        }
    }

    @Test
    void interpolatesExplicitVariablesDeterministically() {
        NonSecretVariableInterpolator interpolator =
                new NonSecretVariableInterpolator(Map.of("id", "42", "name", "sample"));
        assertThat(interpolator.interpolate("/${name}/${id}/${id}"))
                .isEqualTo("/sample/42/42");
        assertThat(interpolator.interpolate("unchanged")).isEqualTo("unchanged");
    }

    @Test
    void rejectsUnknownMalformedRecursiveAndOversizedInterpolation() {
        assertInterpolationFailure(Map.of(), "${missing}");
        assertInterpolationFailure(Map.of("name", "value"), "${bad token}");
        assertThatThrownBy(() -> new NonSecretVariableInterpolator(Map.of("a", "${b}")))
                .isInstanceOf(PlaywrightActionException.class);
        assertInterpolationFailure(
                Map.of("large", "x".repeat(NonSecretVariableInterpolator.MAX_EXPANDED_LENGTH)),
                "a${large}");
        HashMap<String, String> tooMany = new HashMap<>();
        for (int index = 0; index <= NonSecretVariableInterpolator.MAX_VARIABLES; index++) {
            tooMany.put("v" + index, "x");
        }
        assertThatThrownBy(() -> new NonSecretVariableInterpolator(tooMany))
                .isInstanceOf(PlaywrightActionException.class);
    }

    @Test
    void rejectsEveryRecursiveAndInvalidVariableBoundaryWithoutFallback() {
        assertVariableDefinitionFailure(Map.of("self", "${self}"), "VARIABLE_INVALID");
        assertVariableDefinitionFailure(Map.of("a", "${b}", "b", "${a}"), "VARIABLE_INVALID");
        assertVariableDefinitionFailure(
                Map.of("a", "${b}", "b", "${c}", "c", "${a}"), "VARIABLE_INVALID");

        HashMap<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "secret-value");
        assertVariableDefinitionFailure(nullKey, "VARIABLE_INVALID");
        HashMap<String, String> nullValue = new HashMap<>();
        nullValue.put("name", null);
        assertVariableDefinitionFailure(nullValue, "VARIABLE_INVALID");
        assertVariableDefinitionFailure(Map.of("", "secret-value"), "VARIABLE_INVALID");
        assertVariableDefinitionFailure(Map.of("a".repeat(65), "secret-value"), "VARIABLE_INVALID");
        assertVariableDefinitionFailure(Map.of("n\u00e4me", "secret-value"), "VARIABLE_INVALID");

        NonSecretVariableInterpolator none = new NonSecretVariableInterpolator(Map.of());
        assertInterpolationCode(none, "${PATH}", "VARIABLE_UNRESOLVED");
        assertInterpolationCode(none, "${user.home}", "VARIABLE_UNRESOLVED");
        assertInterpolationCode(none, "${}", "INTERPOLATION_INVALID");
        assertInterpolationCode(none, "${missing", "INTERPOLATION_INVALID");

        NonSecretVariableInterpolator repeated =
                new NonSecretVariableInterpolator(Map.of("name", "safe"));
        assertThat(repeated.interpolate("${name}/${name}/${name}"))
                .isEqualTo("safe/safe/safe");
    }

    @Test
    void enforcesInterpolationLimitsBeforeEveryAppendPath() {
        String oversizedLiteral = "s".repeat(NonSecretVariableInterpolator.MAX_EXPANDED_LENGTH + 1);
        NonSecretVariableInterpolator empty = new NonSecretVariableInterpolator(Map.of());
        assertInterpolationCode(empty, oversizedLiteral, "INTERPOLATION_LIMIT_EXCEEDED");
        assertInterpolationCode(
                new NonSecretVariableInterpolator(Map.of("v", "x")),
                "s".repeat(NonSecretVariableInterpolator.MAX_EXPANDED_LENGTH) + "${v}",
                "INTERPOLATION_LIMIT_EXCEEDED");

        assertThat(empty.interpolate(
                        "s".repeat(NonSecretVariableInterpolator.MAX_EXPANDED_LENGTH - 1)))
                .hasSize(NonSecretVariableInterpolator.MAX_EXPANDED_LENGTH - 1);
        NonSecretVariableInterpolator boundary =
                new NonSecretVariableInterpolator(Map.of("v", "123456"));
        assertThat(boundary.interpolate(
                        "s".repeat(NonSecretVariableInterpolator.MAX_EXPANDED_LENGTH - 6) + "${v}"))
                .hasSize(NonSecretVariableInterpolator.MAX_EXPANDED_LENGTH);
        assertInterpolationCode(
                new NonSecretVariableInterpolator(Map.of("v", "1234567")),
                "s".repeat(NonSecretVariableInterpolator.MAX_EXPANDED_LENGTH - 6) + "${v}",
                "INTERPOLATION_LIMIT_EXCEEDED");

        String half = "x".repeat(NonSecretVariableInterpolator.MAX_EXPANDED_LENGTH / 2);
        NonSecretVariableInterpolator segments =
                new NonSecretVariableInterpolator(Map.of("a", half, "b", half));
        assertInterpolationCode(segments, "${a}x${b}", "INTERPOLATION_LIMIT_EXCEEDED");
        NonSecretVariableInterpolator repeated =
                new NonSecretVariableInterpolator(Map.of("v", half));
        assertInterpolationCode(repeated, "${v}${v}x", "INTERPOLATION_LIMIT_EXCEEDED");
    }

    @Test
    void enforcesNormalizedSameOriginNavigation() {
        SameOriginNavigationPolicy policy =
                new SameOriginNavigationPolicy("https://Example.test:443/base/");
        assertThat(policy.resolve("child?q=1#part"))
                .isEqualTo(URI.create("https://Example.test:443/base/child?q=1#part"));
        assertThat(policy.resolve("https://example.test/path"))
                .isEqualTo(URI.create("https://example.test/path"));
        assertThat(policy.validateFinal(URI.create("https://example.test/final")))
                .isEqualTo(URI.create("https://example.test/final"));
        assertThat(policy.resolve("?q=1")).isEqualTo(URI.create("https://Example.test:443/base/?q=1"));
        assertThat(policy.resolve("#part")).isEqualTo(URI.create("https://Example.test:443/base/#part"));

        assertThat(new SameOriginNavigationPolicy("http://127.0.0.1/")
                .resolve("http://127.0.0.1:80/path"))
                .isEqualTo(URI.create("http://127.0.0.1:80/path"));
        assertThat(new SameOriginNavigationPolicy("https://[::1]/")
                .resolve("https://[::1]:443/path"))
                .isEqualTo(URI.create("https://[::1]:443/path"));
    }

    @Test
    void rejectsUnsafeCrossOriginAndRedirectUrlsWithoutLeakage() {
        SameOriginNavigationPolicy policy = new SameOriginNavigationPolicy("https://example.test/");
        for (String value : new String[] {
                "http://example.test/secret", "https://other.test/secret",
                "https://example.test:444/secret", "file:///secret", "javascript:secret",
                "data:secret", "about:blank", "blob:https://example.test/id", "chrome://settings",
                "chrome-extension://id/page", "//other.test/secret",
                "https://user:password@example.test/secret", "https:\\other.test\\secret",
                "https://example.test%2f@other.test/secret", "https://b\u00fccher.test/secret",
                "https://example.test./secret", "", "http://["
        }) {
            assertThatThrownBy(() -> policy.resolve(value))
                    .isInstanceOfSatisfying(PlaywrightActionException.class,
                            failure -> assertThat(failure.getMessage()).doesNotContain("secret"));
        }
        assertThatThrownBy(() -> policy.validateFinal(URI.create("https://other.test/secret")))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("REDIRECT_ORIGIN_INVALID"));
        assertThatThrownBy(() -> policy.resolve("x".repeat(SameOriginNavigationPolicy.MAX_URL_LENGTH + 1)))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("NAVIGATION_INVALID"));
        URI oversizedFinal = URI.create(
                "https://example.test/" + "x".repeat(SameOriginNavigationPolicy.MAX_URL_LENGTH));
        assertThatThrownBy(() -> policy.validateFinal(oversizedFinal))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("REDIRECT_ORIGIN_INVALID"));
    }

    private void assertInterpolationFailure(Map<String, String> variables, String input) {
        assertThatThrownBy(() -> new NonSecretVariableInterpolator(variables).interpolate(input))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.getMessage()).doesNotContain(input));
    }

    private void assertVariableDefinitionFailure(Map<String, String> variables, String code) {
        assertThatThrownBy(() -> new NonSecretVariableInterpolator(variables))
                .isInstanceOfSatisfying(PlaywrightActionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.getMessage()).isEqualTo("Variable interpolation is invalid");
                    assertThat(failure.getMessage()).doesNotContain("secret-value");
                });
    }

    private void assertInterpolationCode(
            NonSecretVariableInterpolator interpolator, String input, String code) {
        assertThatThrownBy(() -> interpolator.interpolate(input))
                .isInstanceOfSatisfying(PlaywrightActionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.getMessage()).isEqualTo("Variable interpolation is invalid");
                    assertThat(failure.getMessage()).doesNotContain(input);
                });
    }
}
