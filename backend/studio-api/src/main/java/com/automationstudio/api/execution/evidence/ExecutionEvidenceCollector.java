package com.automationstudio.api.execution.evidence;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.lifecycle.ExecutionResult;

public interface ExecutionEvidenceCollector {

    ExecutionEvidence collect(ExecutionContext context, ExecutionResult result);

    void validate(ExecutionContext context, ExecutionEvidence evidence);

    ExecutionEvidence normalize(ExecutionEvidence evidence);
}
