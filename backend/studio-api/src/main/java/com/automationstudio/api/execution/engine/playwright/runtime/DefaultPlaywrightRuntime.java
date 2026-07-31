package com.automationstudio.api.execution.engine.playwright.runtime;

import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightBrowser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightRuntimeProperties;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultPlaywrightRuntime implements PlaywrightRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPlaywrightRuntime.class);

    private final PlaywrightRuntimeProperties properties;
    private final PlaywrightSdk sdk;
    private final MonotonicTicker ticker;

    public DefaultPlaywrightRuntime(PlaywrightRuntimeProperties properties) {
        this(properties, new ChromiumPlaywrightSdk(), System::nanoTime);
    }

    DefaultPlaywrightRuntime(
            PlaywrightRuntimeProperties properties,
            PlaywrightSdk sdk,
            MonotonicTicker ticker) {
        this.properties = Objects.requireNonNull(properties, "Runtime properties are required");
        this.sdk = Objects.requireNonNull(sdk, "Playwright SDK adapter is required");
        this.ticker = Objects.requireNonNull(ticker, "Monotonic ticker is required");
    }

    @Override
    public PlaywrightRuntimeSession open(PlaywrightExecutionConfiguration configuration) {
        validate(configuration);
        LOGGER.info("Playwright runtime creation started");

        PlaywrightSdk.SdkPlaywright playwright = null;
        PlaywrightSdk.SdkBrowser browser = null;
        PlaywrightSdk.SdkBrowserContext context = null;
        PlaywrightSdk.SdkPage page = null;
        String failureCode = "PLAYWRIGHT_UNAVAILABLE";
        long startupStarted = ticker.readNanos();
        try {
            playwright = sdk.create();
            if (properties.executablePath().isEmpty()
                    && !playwright.isDefaultChromiumAvailable()) {
                throw new PlaywrightRuntimeException(
                        "BROWSER_EXECUTABLE_UNAVAILABLE",
                        "Chromium executable is unavailable");
            }
            failureCode = "BROWSER_LAUNCH_FAILED";
            browser = playwright.launch(configuration, properties);
            Duration startupDuration = elapsed(startupStarted, ticker.readNanos());
            LOGGER.info("Chromium launch completed");

            failureCode = "CONTEXT_CREATION_FAILED";
            context = browser.createContext(configuration);
            LOGGER.info("Playwright browser context created");

            failureCode = "PAGE_CREATION_FAILED";
            page = context.createBlankPage();
            LOGGER.info("Playwright blank page created");

            PlaywrightRuntimeMetrics metrics =
                    PlaywrightRuntimeMetrics.startup(startupDuration);
            return new DefaultPlaywrightRuntimeSession(
                    playwright,
                    browser,
                    context,
                    page,
                    new PlaywrightRuntimeResult(metrics));
        } catch (RuntimeException startupFailure) {
            PlaywrightRuntimeException failure =
                    startupFailure instanceof PlaywrightRuntimeException typedFailure
                            ? typedFailure
                            : new PlaywrightRuntimeException(
                                    failureCode, safeMessage(failureCode), startupFailure);
            cleanupAfterFailedStartup(page, context, browser, playwright, failure);
            LOGGER.warn("Playwright runtime creation failed with code {}", failure.code());
            throw failure;
        }
    }

    private void validate(PlaywrightExecutionConfiguration configuration) {
        if (configuration == null) {
            throw invalidConfiguration();
        }
        if (configuration.browser() != PlaywrightBrowser.CHROMIUM
                || !configuration.headless()
                || configuration.viewportWidth() <= 0
                || configuration.viewportHeight() <= 0) {
            throw invalidConfiguration();
        }
        Duration timeout = properties.browserStartupTimeout();
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw invalidRuntimeProperties();
        }
        String executablePath = properties.executablePath();
        if (executablePath == null) {
            throw invalidRuntimeProperties();
        }
        if (!executablePath.isEmpty()) {
            try {
                Path executable = Path.of(executablePath);
                if (!executable.isAbsolute()
                        || !Files.isRegularFile(executable)
                        || !Files.isExecutable(executable)) {
                    throw new PlaywrightRuntimeException(
                            "BROWSER_EXECUTABLE_UNAVAILABLE",
                            "Configured Chromium executable is unavailable");
                }
            } catch (InvalidPathException exception) {
                throw invalidRuntimeProperties();
            }
        }
    }

    private void cleanupAfterFailedStartup(
            AutoCloseable page,
            AutoCloseable context,
            AutoCloseable browser,
            AutoCloseable playwright,
            PlaywrightRuntimeException primary) {
        cleanup(page, "PAGE_CLOSE_FAILED", primary);
        cleanup(context, "CONTEXT_CLOSE_FAILED", primary);
        cleanup(browser, "BROWSER_CLOSE_FAILED", primary);
        cleanup(playwright, "PLAYWRIGHT_CLOSE_FAILED", primary);
    }

    private void cleanup(
            AutoCloseable resource, String code, PlaywrightRuntimeException primary) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception cleanupFailure) {
            primary.addSuppressed(new PlaywrightRuntimeException(
                    code, "Playwright runtime cleanup failed", cleanupFailure));
        }
    }

    private Duration elapsed(long start, long end) {
        long elapsedNanos = end - start;
        if (elapsedNanos < 0) {
            throw new PlaywrightRuntimeException(
                    "STARTUP_TIMING_INVALID",
                    "Playwright browser startup timing is invalid");
        }
        return Duration.ofNanos(elapsedNanos);
    }

    private PlaywrightRuntimeException invalidConfiguration() {
        return new PlaywrightRuntimeException(
                "INVALID_RUNTIME_CONFIGURATION",
                "Playwright runtime configuration is invalid");
    }

    private PlaywrightRuntimeException invalidRuntimeProperties() {
        return new PlaywrightRuntimeException(
                "INVALID_RUNTIME_PROPERTIES",
                "Playwright operator runtime properties are invalid");
    }

    private String safeMessage(String code) {
        return switch (code) {
            case "PLAYWRIGHT_UNAVAILABLE" -> "Playwright runtime is unavailable";
            case "BROWSER_EXECUTABLE_UNAVAILABLE" -> "Chromium executable is unavailable";
            case "BROWSER_LAUNCH_FAILED" -> "Chromium browser could not be launched";
            case "CONTEXT_CREATION_FAILED" -> "Browser context could not be created";
            case "PAGE_CREATION_FAILED" -> "Blank browser page could not be created";
            default -> "Playwright runtime creation failed";
        };
    }
}
