package com.automationstudio.api.execution.engine.playwright.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.engine.playwright.RealBrowserTestSupport;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightBrowser;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightNavigationPolicy;
import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightRuntimeProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("real-browser")
class PlaywrightRealBrowserRuntimeTest {

    @TempDir Path temporaryDirectory;

    @Test
    void launchesProvisionedChromiumNavigatesLoopbackAndClosesSession() throws Exception {
        Path executable = RealBrowserTestSupport.configuredExecutableOrSkip();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<html><body>runtime-smoke</body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        PlaywrightRuntimeSession session = null;
        try {
            DefaultPlaywrightRuntime runtime = new DefaultPlaywrightRuntime(
                    new PlaywrightRuntimeProperties(executable.toString(), Duration.ofSeconds(30)));
            session = runtime.open(configuration());
            assertThat(session).isInstanceOf(PlaywrightActionRuntime.class);
            assertThat(session.isOpen()).isTrue();
            assertThat(session.result().metrics().browserStartupDuration())
                    .isGreaterThanOrEqualTo(Duration.ZERO);
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            session.navigate(uri, Duration.ofSeconds(3));
            assertThat(session.currentUri()).isEqualTo(uri);
        } finally {
            if (session != null) session.close();
            server.stop(0);
        }
        assertThat(session).isNotNull();
        assertThat(session.isOpen()).isFalse();
    }

    @Test
    void rejectsInvalidExplicitExecutableWithoutExposingItsPath() {
        Path missing = temporaryDirectory.resolve("private-browser-path.exe").toAbsolutePath();
        DefaultPlaywrightRuntime runtime = new DefaultPlaywrightRuntime(
                new PlaywrightRuntimeProperties(missing.toString(), Duration.ofSeconds(5)));

        assertThatThrownBy(() -> runtime.open(configuration()))
                .isInstanceOfSatisfying(PlaywrightRuntimeException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("BROWSER_EXECUTABLE_UNAVAILABLE");
                    assertThat(failure).hasMessage("Configured Chromium executable is unavailable");
                    assertThat(failure.getMessage()).doesNotContain(missing.toString());
                });
    }

    private PlaywrightExecutionConfiguration configuration() {
        return new PlaywrightExecutionConfiguration(
                PlaywrightBrowser.CHROMIUM, true, Duration.ofSeconds(3), Duration.ofSeconds(3),
                1024, 768, "en-US", PlaywrightNavigationPolicy.SAME_ORIGIN);
    }
}
