package com.automationstudio.api.execution.engine.playwright;

import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import java.util.Set;

public final class PlaywrightEngineDescriptor {

    public static final String ENGINE_ID = "playwright-java";
    public static final String IMPLEMENTATION_VERSION = "1.61.0";
    @Deprecated(forRemoval = false)
    public static final String ENGINE_NAME = ENGINE_ID;
    @Deprecated(forRemoval = false)
    public static final String ENGINE_VERSION = IMPLEMENTATION_VERSION;

    private static final ExecutionEngineDescriptor DESCRIPTOR =
            new ExecutionEngineDescriptor(
                    ENGINE_ID,
                    IMPLEMENTATION_VERSION,
                    "Playwright Java",
                    Set.of("chromium"),
                    Set.of("declarative-scenario"));

    private PlaywrightEngineDescriptor() {
    }

    public static ExecutionEngineDescriptor descriptor() {
        return DESCRIPTOR;
    }
}
