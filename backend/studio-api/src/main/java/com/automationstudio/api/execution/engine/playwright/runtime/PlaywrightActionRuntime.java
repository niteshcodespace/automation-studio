package com.automationstudio.api.execution.engine.playwright.runtime;

import java.net.URI;
import java.time.Duration;

public interface PlaywrightActionRuntime {
    void navigate(URI uri, Duration timeout);
    void click(String selector, Duration timeout);
    void fill(String selector, String value, Duration timeout);
    boolean isVisible(String selector, Duration timeout);
    String textContent(String selector, Duration timeout);
    URI currentUri();
}
