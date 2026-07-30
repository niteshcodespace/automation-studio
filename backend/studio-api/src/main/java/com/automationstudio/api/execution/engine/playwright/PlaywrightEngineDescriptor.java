package com.automationstudio.api.execution.engine.playwright;

import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import java.util.Set;

public final class PlaywrightEngineDescriptor {

    public static final String ENGINE_NAME = "playwright-java";
    public static final String ENGINE_VERSION = "1.61.0";

    private static final ExecutionEngineDescriptor DESCRIPTOR =
            new ExecutionEngineDescriptor(
                    ENGINE_NAME,
                    ENGINE_VERSION,
                    "Playwright Java",
                    Set.of("chromium"),
                    Set.of("declarative-scenario"));

    private PlaywrightEngineDescriptor() {
    }

    public static ExecutionEngineDescriptor descriptor() {
        return DESCRIPTOR;
    }
}
