package com.automationstudio.api.mapper;

import com.automationstudio.api.dto.ExecutionResponse;
import com.automationstudio.api.dto.ExecutionSummaryResponse;
import com.automationstudio.api.entity.Execution;
import org.springframework.stereotype.Component;

@Component
public class ExecutionMapper {

    public ExecutionResponse toResponse(Execution execution) {
        return new ExecutionResponse(
                execution.getId(),
                execution.getProject().getId(),
                execution.getEnvironment().getId(),
                execution.getTestSuite().getId(),
                execution.getStatus(),
                execution.getRequestedBy(),
                execution.getRequestedAt(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.getTotalTests(),
                execution.getPassedTests(),
                execution.getFailedTests(),
                execution.getErrorMessage(),
                execution.getCreatedAt(),
                execution.getUpdatedAt());
    }

    public ExecutionSummaryResponse toSummaryResponse(Execution execution) {
        return new ExecutionSummaryResponse(
                execution.getId(),
                execution.getProject().getId(),
                execution.getEnvironment().getId(),
                execution.getTestSuite().getId(),
                execution.getStatus(),
                execution.getRequestedBy(),
                execution.getRequestedAt(),
                execution.getFinishedAt(),
                execution.getTotalTests(),
                execution.getPassedTests(),
                execution.getFailedTests());
    }
}
