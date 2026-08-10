package com.automationstudio.engine.conformance;

import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.util.List;
import java.util.UUID;

/** Supplies deterministic, isolated inputs and observations to the reusable contract. */
public interface ExecutionEnginePluginFixture {

    ExecutionEnginePlugin plugin();

    EngineExecutionRequest validRequest();

    EngineExecutionState expectedState();

    List<EngineExecutionRequest> concurrentRequests();

    long validationResourceAcquisitions();

    boolean cleanupObserved(UUID executionId);
}
