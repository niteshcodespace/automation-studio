package com.automationstudio.api.execution.engine.playwright.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChromiumPlaywrightSdkTest {

    private static final String SELECTOR = "form.oxd-form";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final Page page = mock(Page.class);
    private final Locator locator = mock(Locator.class);
    private final PlaywrightSdk.SdkPage handle = new ChromiumPlaywrightSdk.PageHandle(page);

    @Test
    void visibleElementSucceedsAndPassesTheConfiguredTimeoutToLocatorWait() {
        when(page.locator(SELECTOR)).thenReturn(locator);

        assertThat(handle.isVisible(SELECTOR, TIMEOUT)).isTrue();

        ArgumentCaptor<Locator.WaitForOptions> options =
                ArgumentCaptor.forClass(Locator.WaitForOptions.class);
        verify(locator).waitFor(options.capture());
        assertThat(options.getValue().state).isEqualTo(WaitForSelectorState.VISIBLE);
        assertThat(options.getValue().timeout).isEqualTo(TIMEOUT.toMillis());
    }

    @Test
    void elementBecomingVisibleDuringTheBoundedWaitSucceeds() throws Exception {
        CountDownLatch waitStarted = new CountDownLatch(1);
        CountDownLatch becameVisible = new CountDownLatch(1);
        when(page.locator(SELECTOR)).thenReturn(locator);
        doAnswer(invocation -> {
            waitStarted.countDown();
            if (!becameVisible.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for controlled visibility");
            }
            return null;
        }).when(locator).waitFor(any(Locator.WaitForOptions.class));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> handle.isVisible(SELECTOR, TIMEOUT));
            assertThat(waitStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result.isDone()).isFalse();
            becameVisible.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            becameVisible.countDown();
        }
    }

    @Test
    void visibilityTimeoutReturnsFalseWithoutExposingThePlaywrightMessage() {
        when(page.locator(SELECTOR)).thenReturn(locator);
        doThrow(new TimeoutError("raw page and selector details"))
                .when(locator).waitFor(any(Locator.WaitForOptions.class));

        assertThat(handle.isVisible(SELECTOR, TIMEOUT)).isFalse();
    }

    @Test
    void malformedSelectorOrSdkFailureRemainsAnInfrastructureFailure() {
        PlaywrightException failure = new PlaywrightException("raw selector details");
        when(page.locator(SELECTOR)).thenThrow(failure);

        assertThatThrownBy(() -> handle.isVisible(SELECTOR, TIMEOUT)).isSameAs(failure);
    }
}
