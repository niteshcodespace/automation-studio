package com.automationstudio.engine.conformance;

import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionState;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Supplies deterministic, isolated inputs and observations to the reusable contract. */
public interface ExecutionEnginePluginFixture {

    ExecutionEnginePlugin plugin();

    EngineExecutionRequest validRequest();

    EngineExecutionState expectedState();

    List<EngineExecutionRequest> concurrentRequests();

    long validationResourceAcquisitions();

    boolean cleanupObserved(UUID executionId);

    /** Expected deterministic rejection for passive providers; executable providers return null. */
    default ExecutionFailureExpectation expectedExecutionFailure() { return null; }

    /** Provider-neutral failure type and identity assertion for deterministic execution rejection. */
    record ExecutionFailureExpectation(
            Class<? extends Throwable> type, Consumer<Throwable> identityAssertion) {
        public ExecutionFailureExpectation {
            if (type == null || identityAssertion == null) {
                throw new IllegalArgumentException("Failure expectation must be complete");
            }
        }

        public void assertIdentity(Throwable failure) {
            identityAssertion.accept(failure);
        }
    }
}
