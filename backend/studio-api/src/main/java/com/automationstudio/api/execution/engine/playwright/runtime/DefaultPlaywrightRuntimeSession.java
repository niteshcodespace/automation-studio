package com.automationstudio.api.execution.engine.playwright.runtime;

import java.net.URI;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DefaultPlaywrightRuntimeSession implements PlaywrightRuntimeSession {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DefaultPlaywrightRuntimeSession.class);

    private final PlaywrightSdk.SdkPlaywright playwright;
    private final PlaywrightSdk.SdkBrowser browser;
    private final PlaywrightSdk.SdkBrowserContext context;
    private final PlaywrightSdk.SdkPage page;
    private final PlaywrightRuntimeResult result;
    private volatile State state = State.OPEN;
    private PlaywrightRuntimeException closeFailure;

    DefaultPlaywrightRuntimeSession(
            PlaywrightSdk.SdkPlaywright playwright,
            PlaywrightSdk.SdkBrowser browser,
            PlaywrightSdk.SdkBrowserContext context,
            PlaywrightSdk.SdkPage page,
            PlaywrightRuntimeResult result) {
        this.playwright = playwright;
        this.browser = browser;
        this.context = context;
        this.page = page;
        this.result = result;
    }

    @Override
    public boolean isOpen() {
        return state == State.OPEN;
    }

    @Override
    public PlaywrightRuntimeResult result() {
        return result;
    }

    @Override
    public void navigate(URI uri, Duration timeout) {
        requireOpen();
        perform("NAVIGATION_FAILED", "Playwright navigation failed",
                () -> page.navigate(uri.toASCIIString(), timeout));
    }

    @Override
    public void click(String selector, Duration timeout) {
        requireOpen();
        perform("CLICK_FAILED", "Playwright click failed", () -> page.click(selector, timeout));
    }

    @Override
    public void fill(String selector, String value, Duration timeout) {
        requireOpen();
        perform("FILL_FAILED", "Playwright fill failed", () -> page.fill(selector, value, timeout));
    }

    @Override
    public boolean isVisible(String selector, Duration timeout) {
        requireOpen();
        return query("VISIBILITY_QUERY_FAILED", "Playwright visibility query failed",
                () -> page.isVisible(selector, timeout));
    }

    @Override
    public String textContent(String selector, Duration timeout) {
        requireOpen();
        return query("TEXT_QUERY_FAILED", "Playwright text query failed",
                () -> page.textContent(selector, timeout));
    }

    @Override
    public URI currentUri() {
        requireOpen();
        return query("CURRENT_URL_INVALID", "Playwright current URL is invalid",
                () -> URI.create(page.url()));
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            rethrowCloseFailure();
            return;
        }
        if (state == State.CLOSING) {
            return;
        }
        state = State.CLOSING;
        closeFailure = close(page, "PAGE_CLOSE_FAILED", closeFailure);
        closeFailure = close(context, "CONTEXT_CLOSE_FAILED", closeFailure);
        closeFailure = close(browser, "BROWSER_CLOSE_FAILED", closeFailure);
        closeFailure = close(playwright, "PLAYWRIGHT_CLOSE_FAILED", closeFailure);
        state = State.CLOSED;
        if (closeFailure == null) {
            LOGGER.info("Playwright runtime closed");
        } else {
            LOGGER.warn(
                    "Playwright runtime cleanup failed with code {}",
                    closeFailure.code());
        }
        rethrowCloseFailure();
    }

    private PlaywrightRuntimeException close(
            AutoCloseable resource,
            String code,
            PlaywrightRuntimeException prior) {
        try {
            resource.close();
        } catch (Exception exception) {
            PlaywrightRuntimeException current = new PlaywrightRuntimeException(
                    code, "Playwright runtime cleanup failed", exception);
            if (prior == null) {
                return current;
            }
            prior.addSuppressed(current);
        }
        return prior;
    }

    private void rethrowCloseFailure() {
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new PlaywrightRuntimeException(
                    "RUNTIME_SESSION_CLOSED", "Playwright runtime session is closed");
        }
    }

    private void perform(String code, String message, RuntimeAction action) {
        query(code, message, () -> {
            action.run();
            return null;
        });
    }

    private <T> T query(String code, String message, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (PlaywrightRuntimeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PlaywrightRuntimeException(code, message, exception);
        }
    }

    @FunctionalInterface
    private interface RuntimeAction {
        void run();
    }

    private enum State {
        OPEN,
        CLOSING,
        CLOSED
    }
}
