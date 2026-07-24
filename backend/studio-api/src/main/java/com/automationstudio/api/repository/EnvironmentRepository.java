package com.automationstudio.api.repository;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.entity.Environment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EnvironmentRepository
        extends JpaRepository<Environment, UUID>, JpaSpecificationExecutor<Environment> {

    Optional<Environment> findByProjectIdAndId(UUID projectId, UUID id);

    Optional<Environment> findByProjectIdAndName(UUID projectId, String name);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    List<Environment> findByProjectId(UUID projectId);

    Page<Environment> findByProjectId(UUID projectId, Pageable pageable);

    Page<Environment> findByProjectIdAndStatus(
            UUID projectId, EnvironmentStatus status, Pageable pageable);

    Page<Environment> findByProjectIdAndType(
            UUID projectId, EnvironmentType type, Pageable pageable);

    Page<Environment> findByProjectIdAndIsDefault(
            UUID projectId, boolean isDefault, Pageable pageable);

    Optional<Environment> findByProjectIdAndIsDefaultTrue(UUID projectId);

    long countByProjectId(UUID projectId);
}
