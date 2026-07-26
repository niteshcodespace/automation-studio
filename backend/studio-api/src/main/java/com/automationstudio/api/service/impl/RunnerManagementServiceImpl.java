package com.automationstudio.api.service.impl;

import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.service.RunnerManagementService;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunnerManagementServiceImpl implements RunnerManagementService {

    private final RunnerRepository runnerRepository;

    public RunnerManagementServiceImpl(RunnerRepository runnerRepository) {
        this.runnerRepository = runnerRepository;
    }

    @Override
    @Transactional
    public Runner changeStatus(
            UUID runnerId,
            long expectedVersion,
            RunnerStatus requestedStatus) {
        if (runnerId == null) {
            throw new InvalidRequestException("Runner ID must not be null");
        }
        if (expectedVersion < 0) {
            throw new InvalidRequestException("Expected runner version must not be negative");
        }
        if (requestedStatus == null) {
            throw new InvalidRequestException("Runner status must not be null");
        }

        Runner runner = runnerRepository.findByIdForUpdate(runnerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Runner not found: " + runnerId));
        if (runner.getVersion() != expectedVersion) {
            throw new ResourceConflictException("Runner version is stale");
        }
        if (runner.getStatus() == requestedStatus) {
            return runner;
        }
        if (runner.getStatus() == RunnerStatus.DEREGISTERED) {
            throw new ResourceConflictException(
                    "Deregistered runner status cannot be changed");
        }

        runner.updateStatus(requestedStatus);
        try {
            return runnerRepository.saveAndFlush(runner);
        } catch (DataAccessException exception) {
            throw new ResourceConflictException(
                    "Runner status change conflicts with persisted state");
        }
    }
}
