package com.automationstudio.api.execution.engine.playwright;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RealBrowserTestSupport {

    public static final String EXECUTABLE_PROPERTY =
            "automation.runner.playwright.executable-path";

    private RealBrowserTestSupport() {}

    public static Path configuredExecutableOrSkip() {
        String configured = System.getProperty(EXECUTABLE_PROPERTY, "");
        assumeTrue(!configured.isBlank(),
                () -> "Real-browser test requires -D" + EXECUTABLE_PROPERTY);
        Path executable = Path.of(configured);
        assumeTrue(executable.isAbsolute() && Files.isRegularFile(executable)
                        && Files.isExecutable(executable),
                "Configured real-browser executable is unavailable");
        return executable;
    }
}
