package com.automationstudio.engine.karate;

import com.automationstudio.engine.sdk.PreparedSourceAccess;
import java.util.List;
import java.util.UUID;

interface KarateWorkerRuntime {
    void prove(UUID executionId, PreparedSourceAccess source, List<String> logicalPaths);
}
