package com.automationstudio.api.execution.engine.playwright.runtime;

import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightRuntimeProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

final class ChromiumPlaywrightSdk implements PlaywrightSdk {

    @Override
    public SdkPlaywright create() {
        return new PlaywrightHandle(Playwright.create());
    }

    private record PlaywrightHandle(Playwright delegate) implements SdkPlaywright {

        @Override
        public boolean isDefaultChromiumAvailable() {
            Path executable = Path.of(delegate.chromium().executablePath());
            return Files.isRegularFile(executable) && Files.isExecutable(executable);
        }

        @Override
        public SdkBrowser launch(
                PlaywrightExecutionConfiguration configuration,
                PlaywrightRuntimeProperties properties) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                    .setHeadless(configuration.headless())
                    .setTimeout(properties.browserStartupTimeout().toMillis());
            if (!properties.executablePath().isEmpty()) {
                options.setExecutablePath(Path.of(properties.executablePath()));
            }
            return new BrowserHandle(delegate.chromium().launch(options));
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private record BrowserHandle(Browser delegate) implements SdkBrowser {

        @Override
        public SdkBrowserContext createContext(
                PlaywrightExecutionConfiguration configuration) {
            Browser.NewContextOptions options = new Browser.NewContextOptions()
                    .setViewportSize(
                            configuration.viewportWidth(), configuration.viewportHeight());
            if (configuration.locale() != null) {
                options.setLocale(configuration.locale());
            }
            return new BrowserContextHandle(delegate.newContext(options));
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private record BrowserContextHandle(BrowserContext delegate)
            implements SdkBrowserContext {

        @Override
        public SdkPage createBlankPage() {
            return new PageHandle(delegate.newPage());
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    record PageHandle(Page delegate) implements SdkPage {

        @Override
        public void navigate(String uri, Duration timeout) {
            delegate.navigate(uri, new Page.NavigateOptions().setTimeout(timeout.toMillis()));
        }

        @Override
        public void click(String selector, Duration timeout) {
            delegate.click(selector, new Page.ClickOptions().setTimeout(timeout.toMillis()));
        }

        @Override
        public void fill(String selector, String value, Duration timeout) {
            delegate.fill(selector, value, new Page.FillOptions().setTimeout(timeout.toMillis()));
        }

        @Override
        public boolean isVisible(String selector, Duration timeout) {
            try {
                delegate.locator(selector).waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(timeout.toMillis()));
                return true;
            } catch (TimeoutError timeoutFailure) {
                return false;
            }
        }

        @Override
        public String textContent(String selector, Duration timeout) {
            return delegate.textContent(
                    selector, new Page.TextContentOptions().setTimeout(timeout.toMillis()));
        }

        @Override
        public String url() {
            return delegate.url();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
