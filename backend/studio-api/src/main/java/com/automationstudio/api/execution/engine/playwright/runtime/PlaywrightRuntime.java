package com.automationstudio.api.execution.engine.playwright.runtime;

import com.automationstudio.api.execution.engine.playwright.configuration.PlaywrightExecutionConfiguration;

public interface PlaywrightRuntime {

    PlaywrightRuntimeSession open(PlaywrightExecutionConfiguration configuration);
}
