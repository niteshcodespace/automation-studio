package com.automationstudio.api.execution.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import org.junit.jupiter.api.Test;

class ExecutionEngineInvokerTest {

    private final ExecutionEngineInvoker invoker = new ExecutionEngineInvoker();

    @Test
    void delegatesToProviderNeutralEngineContract() {
        ExecutionEngine engine = mock(ExecutionEngine.class);
        ExecutionContext context = mock(ExecutionContext.class);
        ExecutionResult expected = mock(ExecutionResult.class);
        when(engine.execute(context)).thenReturn(expected);

        assertThat(invoker.invoke(engine, context)).isSameAs(expected);
    }

    @Test
    void preservesEngineExceptionForLifecycleNormalization() {
        ExecutionEngine engine = mock(ExecutionEngine.class);
        ExecutionContext context = mock(ExecutionContext.class);
        when(engine.execute(context)).thenThrow(new IllegalStateException("provider detail"));

        assertThatThrownBy(() -> invoker.invoke(engine, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider detail");
    }
}
