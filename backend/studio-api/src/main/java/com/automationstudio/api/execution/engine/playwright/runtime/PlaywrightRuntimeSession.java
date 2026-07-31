package com.automationstudio.api.execution.engine.playwright.runtime;

public interface PlaywrightRuntimeSession extends AutoCloseable {

    boolean isOpen();

    PlaywrightRuntimeResult result();

    @Override
    void close();
}
