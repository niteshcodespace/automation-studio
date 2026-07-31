package com.automationstudio.api.execution.engine.playwright.action;

@FunctionalInterface
interface ActionMonotonicTicker {
    long readNanos();
}
