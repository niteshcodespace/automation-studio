package com.automationstudio.engine.karate;

import com.automationstudio.engine.sdk.PreparedSourceAccess;
import java.util.List;
import java.util.UUID;
import com.automationstudio.engine.sdk.ExecutionSecretAccess;

interface KarateWorkerRuntime {
    WorkerExecutionResult execute(UUID executionId, PreparedSourceAccess source, List<String> projectedPaths,
            List<String> featurePaths, KarateSuiteConfiguration configuration,
            java.util.Map<String,String> variables, String admittedBaseUrl, ExecutionSecretAccess secretAccess);

    record WorkerExecutionResult(String outcome,int features,int scenarios,int passed,int failed,String diagnostic) {
        public WorkerExecutionResult { if(!java.util.Set.of("SUCCEEDED","FAILED","CANCELLED").contains(outcome)||features<0||scenarios<0||passed<0||failed<0||passed+failed!=scenarios||diagnostic==null||!diagnostic.matches("[A-Z_]{2,40}"))throw new IllegalArgumentException("Invalid worker result"); }
    }
}
