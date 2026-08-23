package com.automationstudio.engine.selenium;

import com.automationstudio.engine.selenium.manifest.SeleniumManifest;

@FunctionalInterface
interface SeleniumExecutionRuntime {
    void execute(SeleniumManifest manifest, SeleniumSuiteConfiguration configuration);
}
