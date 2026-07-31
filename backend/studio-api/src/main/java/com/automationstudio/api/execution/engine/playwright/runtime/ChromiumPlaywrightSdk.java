package com.automationstudio.api.execution.engine.playwright.runtime;

import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightRuntimeProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private record PageHandle(Page delegate) implements SdkPage {

        @Override
        public void close() {
            delegate.close();
        }
    }
}
