package com.automationstudio.api.execution.engine.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightBrowser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightNavigationPolicy;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightSelector;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import com.automationstudio.api.execution.engine.playwright.runtime.PlaywrightActionRuntime;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaywrightActionExecutorsTest {
    @Test
    void sixExecutorsInvokeOnlyTheBoundedRuntimeFacade() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.visible = true;
        runtime.text = "Hello Ada";
        runtime.uri = URI.create("https://example.test/users/42");
        PlaywrightActionExecutionContext context = context(runtime);

        assertSuccess(new NavigateActionExecutor().execute(step(
                "n", PlaywrightActionType.NAVIGATE, null, "/users/${id}", null, null, null), context));
        assertSuccess(new ClickActionExecutor().execute(step(
                "c", PlaywrightActionType.CLICK, "#save", null, null, null, Duration.ofSeconds(1)), context));
        assertSuccess(new FillActionExecutor().execute(step(
                "f", PlaywrightActionType.FILL, "#name", null, "${name}", null, null), context));
        assertSuccess(new AssertVisibleActionExecutor().execute(step(
                "v", PlaywrightActionType.ASSERT_VISIBLE, "#result", null, null, null, null), context));
        assertSuccess(new AssertTextActionExecutor().execute(step(
                "t", PlaywrightActionType.ASSERT_TEXT, "#result", null, null, "Hello ${name}", null), context));
        assertSuccess(new AssertUrlActionExecutor().execute(step(
                "u", PlaywrightActionType.ASSERT_URL, null, null, null, "/users/${id}", null), context));

        assertThat(runtime.calls).containsExactly(
                "navigate:https://example.test/users/42:30000",
                "current-uri",
                "click:resolved-css:1000",
                "fill:resolved-css:Ada:30000",
                "visible:resolved-css:30000",
                "text:resolved-css:30000",
                "current-uri");
    }

    @Test
    void assertionMismatchesReturnSanitizedOutcomes() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.uri = URI.create("https://example.test/actual-secret");
        runtime.text = "actual-secret";
        PlaywrightActionExecutionContext context = context(runtime);

        PlaywrightActionOutcome visible = new AssertVisibleActionExecutor().execute(step(
                "v", PlaywrightActionType.ASSERT_VISIBLE, "#secret", null, null, null, null), context);
        PlaywrightActionOutcome text = new AssertTextActionExecutor().execute(step(
                "t", PlaywrightActionType.ASSERT_TEXT, "#secret", null, null, "expected-secret", null), context);
        PlaywrightActionOutcome url = new AssertUrlActionExecutor().execute(step(
                "u", PlaywrightActionType.ASSERT_URL, null, null, null, "/expected-secret", null), context);

        assertThat(List.of(visible, text, url))
                .allSatisfy(outcome -> {
                    assertThat(outcome.status()).isEqualTo(PlaywrightActionOutcome.Status.ASSERTION_FAILED);
                    assertThat(outcome.toString()).doesNotContain("secret");
                });
        assertThat(visible.reasonCode()).isEqualTo("VISIBLE_ASSERTION_FAILED");
    }

    @Test
    void runtimeFailuresAreSanitizedAndWrongActionFailsClosed() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.failure = new RuntimeException("selector and host secret");
        assertThatThrownBy(() -> new ClickActionExecutor().execute(step(
                        "c", PlaywrightActionType.CLICK, "#secret", null, null, null, null), context(runtime)))
                .isInstanceOfSatisfying(PlaywrightActionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ACTION_EXECUTION_FAILED");
                    assertThat(failure.getMessage()).doesNotContain("secret");
                });
        assertThatThrownBy(() -> new ClickActionExecutor().execute(step(
                        "n", PlaywrightActionType.NAVIGATE, null, "/", null, null, null), context(new FakeRuntime())))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ACTION_TYPE_INVALID"));
    }

    private PlaywrightActionExecutionContext context(FakeRuntime runtime) {
        return new PlaywrightActionExecutionContext(
                "scenario-1", runtime, configuration(), selector -> "resolved-css",
                new NonSecretVariableInterpolator(Map.of("id", "42", "name", "Ada")),
                new SameOriginNavigationPolicy("https://example.test/"));
    }

    private PlaywrightExecutionConfiguration configuration() {
        return new PlaywrightExecutionConfiguration(
                PlaywrightBrowser.CHROMIUM, true, Duration.ofSeconds(30), Duration.ofSeconds(30),
                1280, 720, null, PlaywrightNavigationPolicy.SAME_ORIGIN);
    }

    private PlaywrightStep step(
            String id, PlaywrightActionType type, String selector, String url,
            String value, String expected, Duration timeout) {
        return new PlaywrightStep(id, type,
                selector == null ? null : new PlaywrightSelector(selector),
                url, value, expected, timeout);
    }

    private void assertSuccess(PlaywrightActionOutcome outcome) {
        assertThat(outcome.status()).isEqualTo(PlaywrightActionOutcome.Status.SUCCESS);
    }

    private static final class FakeRuntime implements PlaywrightActionRuntime {
        private final List<String> calls = new ArrayList<>();
        private URI uri = URI.create("https://example.test/");
        private boolean visible;
        private String text;
        private RuntimeException failure;

        @Override public void navigate(URI target, Duration timeout) {
            fail(); calls.add("navigate:" + target + ":" + timeout.toMillis()); uri = target;
        }
        @Override public void click(String selector, Duration timeout) {
            fail(); calls.add("click:" + selector + ":" + timeout.toMillis());
        }
        @Override public void fill(String selector, String value, Duration timeout) {
            fail(); calls.add("fill:" + selector + ":" + value + ":" + timeout.toMillis());
        }
        @Override public boolean isVisible(String selector, Duration timeout) {
            fail(); calls.add("visible:" + selector + ":" + timeout.toMillis()); return visible;
        }
        @Override public String textContent(String selector, Duration timeout) {
            fail(); calls.add("text:" + selector + ":" + timeout.toMillis()); return text;
        }
        @Override public URI currentUri() { fail(); calls.add("current-uri"); return uri; }
        private void fail() { if (failure != null) throw failure; }
    }
}
