package com.automationstudio.api.service;

import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.entity.Runner;
import java.util.UUID;

public interface RunnerManagementService {

    Runner changeStatus(UUID runnerId, long expectedVersion, RunnerStatus status);
}
