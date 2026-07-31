package com.automationstudio.api.execution.engine.playwright.runtime;

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

    private enum State {
        OPEN,
        CLOSING,
        CLOSED
    }
}
