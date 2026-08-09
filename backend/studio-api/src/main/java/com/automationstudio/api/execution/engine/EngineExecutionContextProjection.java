package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionVariable;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineIdentity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EngineExecutionContextProjection {

    private EngineExecutionContextProjection() {}

    public static EngineExecutionContext from(ExecutionContext context) {
        ExecutionContext source = Objects.requireNonNull(context, "Execution context must not be null");
        Map<String, String> variables = new LinkedHashMap<>();
        for (Map.Entry<String, ExecutionVariable> entry : source.variables().entrySet()) {
            ExecutionVariable variable = entry.getValue();
            if (variable != null && entry.getKey().equals(variable.name())
                    && variable.value() instanceof String value) {
                variables.put(entry.getKey(), value);
            }
        }
        return new EngineExecutionContext(
                source.executionId(),
                new EngineIdentity(source.suite().engineId(), source.suite().engineVersion()),
                source.suite().suiteReference(),
                source.suite().configuration(),
                source.environment().baseUrl(),
                source.environment().configuration(),
                variables);
    }
}
