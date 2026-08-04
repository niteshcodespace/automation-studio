package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.secret.ResolvedSecret;

@FunctionalInterface
public interface SensitiveFillValueResolver {

    ResolvedSecret resolve(String logicalName);
}
