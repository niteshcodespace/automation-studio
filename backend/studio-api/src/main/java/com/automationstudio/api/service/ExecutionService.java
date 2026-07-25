package com.automationstudio.api.service;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.service.command.CreateExecutionCommand;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ExecutionService {

    Execution create(UUID projectId, String requester, CreateExecutionCommand command);

    Execution get(UUID projectId, UUID executionId);

    Page<Execution> list(UUID projectId, ExecutionStatus status, Pageable pageable);
}
