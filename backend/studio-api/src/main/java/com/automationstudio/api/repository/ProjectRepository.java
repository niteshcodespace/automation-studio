package com.automationstudio.api.repository;

import com.automationstudio.api.entity.Project;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    boolean existsByWorkspaceIdAndName(UUID workspaceId, String name);

    List<Project> findAllByWorkspaceId(UUID workspaceId);

    Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select project from Project project where project.id = :projectId")
    Optional<Project> findByIdForUpdate(@Param("projectId") UUID projectId);
}
