package com.automationstudio.api.execution.engine.playwright.runtime;

@FunctionalInterface
interface MonotonicTicker {

    long readNanos();
}
