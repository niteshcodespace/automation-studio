package com.automationstudio.api.execution.secret;

public interface ExecutionSecretProvider {

    String providerId();

    ResolvedSecret resolve(Object reference);
}
