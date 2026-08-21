package com.automationstudio.engine.sdk;

/** One execution-scoped, provider-neutral forced-teardown handle. */
@FunctionalInterface
public interface ExecutionTeardown {

    void teardown();
}
