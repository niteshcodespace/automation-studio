package com.automationstudio.api.execution.secret.provider.environment;

@FunctionalInterface
public interface EnvironmentVariableLookup {

    String get(String name);
}
