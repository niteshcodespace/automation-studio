package com.automationstudio.api.service;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.service.command.CreateEnvironmentCommand;
import com.automationstudio.api.service.command.UpdateEnvironmentCommand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EnvironmentService {

    Environment create(UUID projectId, CreateEnvironmentCommand command);

    Environment get(UUID projectId, UUID environmentId);

    Page<Environment> list(
            UUID projectId,
            EnvironmentStatus status,
            EnvironmentType type,
            Boolean isDefault,
            Pageable pageable);

    Optional<Environment> getDefault(UUID projectId);

    Environment update(
            UUID projectId,
            UUID environmentId,
            long expectedVersion,
            UpdateEnvironmentCommand command);

    Environment changeStatus(
            UUID projectId,
            UUID environmentId,
            long expectedVersion,
            EnvironmentStatus status);

    Environment changeDefault(
            UUID projectId,
            UUID environmentId,
            long expectedVersion,
            boolean isDefault);

    void delete(UUID projectId, UUID environmentId, long expectedVersion);
}
