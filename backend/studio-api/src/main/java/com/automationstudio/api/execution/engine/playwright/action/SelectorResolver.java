package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightSelector;

public interface SelectorResolver {
    String resolve(PlaywrightSelector selector);
}
