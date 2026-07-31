package com.automationstudio.api.execution.engine.playwright.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightBrowser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightNavigationPolicy;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightRuntimeProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultPlaywrightRuntimeTest {

    @TempDir Path temporaryDirectory;

    @Test
    void createsRuntimeAndClosesResourcesInReverseOrder() {
        FakeSdk sdk = new FakeSdk();
        DefaultPlaywrightRuntime runtime =
                runtime(sdk, properties("", Duration.ofSeconds(30)), 10, 510);

        PlaywrightRuntimeSession session = runtime.open(configuration());

        assertThat(session.isOpen()).isTrue();
        assertThat(sdk.events).containsExactly("create", "launch", "context", "page");
        assertThat(sdk.configuration.headless()).isTrue();
        assertThat(sdk.configuration.viewportWidth()).isEqualTo(1280);
        assertThat(sdk.configuration.viewportHeight()).isEqualTo(720);
        assertThat(sdk.configuration.locale()).isEqualTo("en-US");
        assertThat(sdk.properties.browserStartupTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(sdk.properties.executablePath()).isEmpty();

        session.close();

        assertThat(session.isOpen()).isFalse();
        assertThat(sdk.events)
                .containsExactly(
                        "create",
                        "launch",
                        "context",
                        "page",
                        "close-page",
                        "close-context",
                        "close-browser",
                        "close-playwright");
    }

    @Test
    void repeatedCloseIsIdempotent() {
        FakeSdk sdk = new FakeSdk();
        PlaywrightRuntimeSession session =
                runtime(sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration());

        session.close();
        session.close();

        assertThat(sdk.events.stream().filter(event -> event.startsWith("close-")))
                .containsExactly(
                        "close-page",
                        "close-context",
                        "close-browser",
                        "close-playwright");
    }

    @Test
    void recordsDeterministicBrowserStartupDuration() {
        PlaywrightRuntimeSession session =
                runtime(new FakeSdk(), properties("", Duration.ofSeconds(30)), 1_000, 6_000)
                        .open(configuration());

        assertThat(session.result().metrics())
                .isEqualTo(new PlaywrightRuntimeMetrics(
                        0, 0, 0, Duration.ZERO, Duration.ofNanos(5_000)));

        session.close();
    }

    @Test
    void acceptsZeroAndNanoTimeWrapCompatibleStartupDurations() {
        PlaywrightRuntimeSession zero =
                runtime(new FakeSdk(), properties("", Duration.ofSeconds(30)), 5, 5)
                        .open(configuration());
        assertThat(zero.result().metrics().browserStartupDuration()).isZero();
        zero.close();

        PlaywrightRuntimeSession wrapped = runtime(
                        new FakeSdk(),
                        properties("", Duration.ofSeconds(30)),
                        Long.MAX_VALUE - 5,
                        Long.MIN_VALUE + 4)
                .open(configuration());
        assertThat(wrapped.result().metrics().browserStartupDuration())
                .isEqualTo(Duration.ofNanos(10));
        wrapped.close();
    }

    @Test
    void rejectsDecreasingTickerWithoutReturningRuntimeResult() {
        FakeSdk sdk = new FakeSdk();

        assertRuntimeFailure(
                () -> runtime(sdk, properties("", Duration.ofSeconds(30)), 10, 9)
                        .open(configuration()),
                "STARTUP_TIMING_INVALID");
        assertThat(sdk.events)
                .containsExactly("create", "launch", "close-browser", "close-playwright");
    }

    @Test
    void rejectsInconsistentOrNegativeMetrics() {
        assertThatThrownBy(() -> new PlaywrightRuntimeMetrics(
                        1, 1, 1, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlaywrightRuntimeMetrics.startup(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closesPlaywrightWhenChromiumIsUnavailable() {
        FakeSdk sdk = new FakeSdk();
        sdk.chromiumAvailable = false;

        assertRuntimeFailure(
                () -> runtime(sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration()),
                "BROWSER_EXECUTABLE_UNAVAILABLE");
        assertThat(sdk.events).containsExactly("create", "close-playwright");
    }

    @Test
    void configuredExecutableDoesNotRequireDefaultChromium() throws Exception {
        Path executable = temporaryDirectory.resolve("operator-chromium.exe");
        java.nio.file.Files.writeString(executable, "fixture");
        executable.toFile().setExecutable(true);
        FakeSdk sdk = new FakeSdk();
        sdk.chromiumAvailable = false;
        PlaywrightRuntimeProperties operatorProperties =
                properties(executable.toAbsolutePath().toString(), Duration.ofSeconds(12));

        PlaywrightRuntimeSession session =
                runtime(sdk, operatorProperties, 0, 1).open(configuration());

        assertThat(sdk.defaultAvailabilityChecks).isZero();
        assertThat(sdk.properties).isSameAs(operatorProperties);
        assertThat(sdk.events).containsExactly("create", "launch", "context", "page");
        session.close();
    }

    @Test
    void defaultChromiumIsValidatedWhenNoExecutableIsConfigured() {
        FakeSdk sdk = new FakeSdk();
        sdk.chromiumAvailable = false;

        assertRuntimeFailure(
                () -> runtime(sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration()),
                "BROWSER_EXECUTABLE_UNAVAILABLE");

        assertThat(sdk.defaultAvailabilityChecks).isOne();
        assertThat(sdk.events).containsExactly("create", "close-playwright");
    }

    @Test
    void partialLaunchFailureClosesPlaywrightAndPreservesPrimaryFailure() {
        FakeSdk sdk = new FakeSdk();
        sdk.failAt = "launch";
        sdk.playwright.closeFailure = true;

        assertThatThrownBy(() -> runtime(
                                sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration()))
                .isInstanceOfSatisfying(
                        PlaywrightRuntimeException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo("BROWSER_LAUNCH_FAILED");
                            assertThat(failure.getMessage()).doesNotContain("host-secret");
                            assertThat(failure.getSuppressed()).hasSize(1);
                            assertThat(failure.getSuppressed()[0])
                                    .isInstanceOfSatisfying(
                                            PlaywrightRuntimeException.class,
                                            cleanup -> assertThat(cleanup.code())
                                                    .isEqualTo("PLAYWRIGHT_CLOSE_FAILED"));
                        });
        assertThat(sdk.events).containsExactly("create", "launch", "close-playwright");
    }

    @Test
    void contextFailureClosesBrowserThenPlaywright() {
        FakeSdk sdk = new FakeSdk();
        sdk.failAt = "context";

        assertRuntimeFailure(
                () -> runtime(sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration()),
                "CONTEXT_CREATION_FAILED");
        assertThat(sdk.events)
                .containsExactly(
                        "create", "launch", "context", "close-browser", "close-playwright");
    }

    @Test
    void pageFailureClosesContextBrowserAndPlaywright() {
        FakeSdk sdk = new FakeSdk();
        sdk.failAt = "page";

        assertRuntimeFailure(
                () -> runtime(sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration()),
                "PAGE_CREATION_FAILED");
        assertThat(sdk.events)
                .containsExactly(
                        "create",
                        "launch",
                        "context",
                        "page",
                        "close-context",
                        "close-browser",
                        "close-playwright");
    }

    @Test
    void closeContinuesAfterFailuresAndReportsSanitizedFailure() {
        FakeSdk sdk = new FakeSdk();
        sdk.page.closeFailure = true;
        sdk.context.closeFailure = true;
        PlaywrightRuntimeSession session =
                runtime(sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration());

        assertThatThrownBy(session::close)
                .isInstanceOfSatisfying(
                        PlaywrightRuntimeException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo("PAGE_CLOSE_FAILED");
                            assertThat(failure.getMessage()).doesNotContain("host-secret");
                            assertThat(failure.getSuppressed()).hasSize(1);
                        });
        assertThat(sdk.events)
                .endsWith(
                        "close-page",
                        "close-context",
                        "close-browser",
                        "close-playwright");
        assertRuntimeFailure(session::close, "PAGE_CLOSE_FAILED");
    }

    @Test
    void concurrentCloseWaitsForSuccessfulCleanupAndClosesResourcesOnce() throws Exception {
        FakeSdk sdk = new FakeSdk();
        sdk.page.blockClose = true;
        PlaywrightRuntimeSession session =
                runtime(sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(session::close);
            assertThat(sdk.page.closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                session.close();
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            sdk.page.allowClose.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertThat(sdk.events.stream().filter(event -> event.startsWith("close-")))
                .containsExactly(
                        "close-page",
                        "close-context",
                        "close-browser",
                        "close-playwright");
    }

    @Test
    void concurrentCloseCallersObserveSameCleanupFailure() throws Exception {
        FakeSdk sdk = new FakeSdk();
        sdk.page.blockClose = true;
        sdk.page.closeFailure = true;
        PlaywrightRuntimeSession session =
                runtime(sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration());

        PlaywrightRuntimeException firstFailure;
        PlaywrightRuntimeException secondFailure;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(session::close);
            assertThat(sdk.page.closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                session.close();
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse();

            sdk.page.allowClose.countDown();
            firstFailure = runtimeFailure(first);
            secondFailure = runtimeFailure(second);
        }

        assertThat(firstFailure).isSameAs(secondFailure);
        assertThat(firstFailure.code()).isEqualTo("PAGE_CLOSE_FAILED");
        assertThat(sdk.events.stream().filter(event -> event.startsWith("close-")))
                .hasSize(4);
    }

    @Test
    void mapsPlaywrightCreationFailureToSanitizedTypedError() {
        FakeSdk sdk = new FakeSdk();
        sdk.failAt = "create";

        assertRuntimeFailure(
                () -> runtime(sdk, properties("", Duration.ofSeconds(30)), 0, 1)
                        .open(configuration()),
                "PLAYWRIGHT_UNAVAILABLE");
    }

    @Test
    void rejectsMissingConfiguredExecutableWithoutExposingPath() {
        String missing = temporaryDirectory.resolve("private-host-path.exe").toString();

        assertThatThrownBy(() -> runtime(
                                new FakeSdk(),
                                properties(missing, Duration.ofSeconds(30)),
                                0,
                                1)
                        .open(configuration()))
                .isInstanceOfSatisfying(
                        PlaywrightRuntimeException.class,
                        failure -> {
                            assertThat(failure.code())
                                    .isEqualTo("BROWSER_EXECUTABLE_UNAVAILABLE");
                            assertThat(failure.getMessage()).doesNotContain(missing);
                        });
    }

    @Test
    void passesConfiguredExecutableOnlyFromOperatorProperties() throws Exception {
        Path executable = temporaryDirectory.resolve("chromium.exe");
        java.nio.file.Files.writeString(executable, "fixture");
        executable.toFile().setExecutable(true);
        FakeSdk sdk = new FakeSdk();
        PlaywrightRuntimeProperties properties =
                properties(executable.toAbsolutePath().toString(), Duration.ofSeconds(12));

        PlaywrightRuntimeSession session =
                runtime(sdk, properties, 0, 1).open(configuration());

        assertThat(sdk.properties).isSameAs(properties);
        assertThat(sdk.properties.executablePath()).isEqualTo(executable.toString());
        assertThat(sdk.properties.browserStartupTimeout()).isEqualTo(Duration.ofSeconds(12));
        session.close();
    }

    @Test
    void rejectsInvalidConfigurationAndRuntimePropertiesDefensively() {
        PlaywrightExecutionConfiguration invalidConfiguration =
                mock(PlaywrightExecutionConfiguration.class);
        when(invalidConfiguration.browser()).thenReturn(null);
        assertRuntimeFailure(
                () -> runtime(
                                new FakeSdk(),
                                properties("", Duration.ofSeconds(30)),
                                0,
                                1)
                        .open(invalidConfiguration),
                "INVALID_RUNTIME_CONFIGURATION");

        PlaywrightRuntimeProperties invalidProperties = mock(PlaywrightRuntimeProperties.class);
        when(invalidProperties.executablePath()).thenReturn("");
        when(invalidProperties.browserStartupTimeout()).thenReturn(Duration.ofMinutes(6));
        assertRuntimeFailure(
                () -> runtime(new FakeSdk(), invalidProperties, 0, 1)
                        .open(configuration()),
                "INVALID_RUNTIME_PROPERTIES");
    }

    private DefaultPlaywrightRuntime runtime(
            FakeSdk sdk, PlaywrightRuntimeProperties properties, long... ticks) {
        AtomicInteger index = new AtomicInteger();
        return new DefaultPlaywrightRuntime(
                properties, sdk, () -> ticks[index.getAndIncrement()]);
    }

    private PlaywrightExecutionConfiguration configuration() {
        return new PlaywrightExecutionConfiguration(
                PlaywrightBrowser.CHROMIUM,
                true,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                1280,
                720,
                "en-US",
                PlaywrightNavigationPolicy.SAME_ORIGIN);
    }

    private PlaywrightRuntimeProperties properties(String path, Duration timeout) {
        return new PlaywrightRuntimeProperties(path, timeout);
    }

    private void assertRuntimeFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        PlaywrightRuntimeException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(code);
                            assertThat(failure.getMessage()).doesNotContain("host-secret");
                        });
    }

    private PlaywrightRuntimeException runtimeFailure(Future<?> future) throws Exception {
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(PlaywrightRuntimeException.class);
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected runtime failure");
        } catch (ExecutionException exception) {
            return (PlaywrightRuntimeException) exception.getCause();
        }
    }

    private static final class FakeSdk implements PlaywrightSdk {
        private final List<String> events = new ArrayList<>();
        private final FakePlaywright playwright = new FakePlaywright();
        private final FakeBrowser browser = new FakeBrowser();
        private final FakeContext context = new FakeContext();
        private final FakePage page = new FakePage();
        private String failAt;
        private boolean chromiumAvailable = true;
        private int defaultAvailabilityChecks;
        private PlaywrightExecutionConfiguration configuration;
        private PlaywrightRuntimeProperties properties;

        @Override
        public SdkPlaywright create() {
            events.add("create");
            fail("create");
            return playwright;
        }

        private void fail(String phase) {
            if (phase.equals(failAt)) {
                throw new RuntimeException("host-secret C:\\private\\browser");
            }
        }

        private class FakePlaywright implements SdkPlaywright {
            private boolean closeFailure;

            @Override
            public boolean isDefaultChromiumAvailable() {
                defaultAvailabilityChecks++;
                return chromiumAvailable;
            }

            @Override
            public SdkBrowser launch(
                    PlaywrightExecutionConfiguration suppliedConfiguration,
                    PlaywrightRuntimeProperties suppliedProperties) {
                events.add("launch");
                configuration = suppliedConfiguration;
                properties = suppliedProperties;
                fail("launch");
                return browser;
            }

            @Override
            public void close() {
                events.add("close-playwright");
                if (closeFailure) {
                    throw new RuntimeException("host-secret");
                }
            }
        }

        private class FakeBrowser implements SdkBrowser {
            @Override
            public SdkBrowserContext createContext(
                    PlaywrightExecutionConfiguration suppliedConfiguration) {
                events.add("context");
                configuration = suppliedConfiguration;
                fail("context");
                return context;
            }

            @Override
            public void close() {
                events.add("close-browser");
            }
        }

        private class FakeContext implements SdkBrowserContext {
            private boolean closeFailure;

            @Override
            public SdkPage createBlankPage() {
                events.add("page");
                fail("page");
                return page;
            }

            @Override
            public void close() {
                events.add("close-context");
                if (closeFailure) {
                    throw new RuntimeException("host-secret");
                }
            }
        }

        private class FakePage implements SdkPage {
            private boolean closeFailure;
            private boolean blockClose;
            private final CountDownLatch closeEntered = new CountDownLatch(1);
            private final CountDownLatch allowClose = new CountDownLatch(1);

            @Override
            public void close() {
                events.add("close-page");
                closeEntered.countDown();
                if (blockClose) {
                    try {
                        if (!allowClose.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out waiting to close fake page");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
                if (closeFailure) {
                    throw new RuntimeException("host-secret");
                }
            }
        }
    }
}
