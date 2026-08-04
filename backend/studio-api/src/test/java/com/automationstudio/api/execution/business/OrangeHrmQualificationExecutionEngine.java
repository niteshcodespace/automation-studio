package com.automationstudio.api.execution.business;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.engine.EngineExecutionRequest;
import com.automationstudio.api.execution.engine.EngineExecutionResult;
import com.automationstudio.api.execution.engine.ExecutionEngine;
import com.automationstudio.api.execution.engine.ExecutionEngineDescriptor;
import com.automationstudio.api.execution.engine.playwright.PlaywrightEngineDescriptor;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class OrangeHrmQualificationExecutionEngine implements ExecutionEngine {

    private final AtomicReference<ExecutionEngine> delegate = new AtomicReference<>();

    @Override
    public ExecutionEngineDescriptor descriptor() {
        return PlaywrightEngineDescriptor.descriptor();
    }

    public void bind(ExecutionEngine engine) {
        Objects.requireNonNull(engine, "AS-025G execution engine must not be null");
        ExecutionEngineDescriptor candidate = Objects.requireNonNull(
                engine.descriptor(), "AS-025G execution engine descriptor must not be null");
        if (!descriptor().engineName().equals(candidate.engineName())
                || !descriptor().engineVersion().equals(candidate.engineVersion())) {
            throw new IllegalArgumentException("AS-025G requires the exact Playwright engine");
        }
        if (!delegate.compareAndSet(null, engine)) {
            throw new IllegalStateException("AS-025G execution engine is already bound");
        }
    }

    public void clear() {
        delegate.set(null);
    }

    boolean isBoundTo(ExecutionEngine engine) {
        return delegate.get() == engine;
    }

    @Override
    public void validate(ExecutionContext context) {
        delegate().validate(context);
    }

    @Override
    public EngineExecutionResult execute(EngineExecutionRequest request) {
        return delegate().execute(request);
    }

    private ExecutionEngine delegate() {
        ExecutionEngine engine = delegate.get();
        if (engine == null) {
            throw new IllegalStateException("AS-025G execution engine is not bound");
        }
        return engine;
    }
}
