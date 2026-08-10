package com.automationstudio.engine.sdk;

import java.util.UUID;

public interface ExecutionSecretAccess {

    UUID executionId();

    ResolvedSecret resolve(String logicalName);
}
