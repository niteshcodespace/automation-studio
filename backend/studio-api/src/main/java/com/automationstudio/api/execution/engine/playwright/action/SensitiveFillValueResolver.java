package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.engine.sdk.ResolvedSecret;

@FunctionalInterface
public interface SensitiveFillValueResolver {

    ResolvedSecret resolve(String logicalName);
}
