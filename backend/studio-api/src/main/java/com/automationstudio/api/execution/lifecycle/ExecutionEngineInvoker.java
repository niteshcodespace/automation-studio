package com.automationstudio.api.execution.lifecycle;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ExecutionEngineInvoker {

    public ExecutionResult invoke(ExecutionEngine engine, ExecutionContext context) {
        Objects.requireNonNull(engine, "Execution engine must not be null");
        Objects.requireNonNull(context, "Execution context must not be null");
        return engine.execute(context);
    }
}
