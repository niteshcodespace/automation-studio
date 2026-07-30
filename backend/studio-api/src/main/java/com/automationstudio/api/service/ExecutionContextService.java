package com.automationstudio.api.service;

import com.automationstudio.api.execution.ExecutionContext;
import java.util.UUID;

public interface ExecutionContextService {

    ExecutionContext createContext(UUID executionId);
}
