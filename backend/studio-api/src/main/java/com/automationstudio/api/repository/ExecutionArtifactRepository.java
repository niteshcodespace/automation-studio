package com.automationstudio.api.repository;

import com.automationstudio.api.entity.ExecutionArtifact;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionArtifactRepository extends JpaRepository<ExecutionArtifact, UUID> {

    boolean existsByStorageReference(String storageReference);

    List<ExecutionArtifact> findByExecutionIdOrderByCreatedAtAscIdAsc(UUID executionId);

    @Query("""
            select artifact
            from ExecutionArtifact artifact
            where artifact.id = :artifactId
              and artifact.execution.id = :executionId
              and artifact.execution.project.id = :projectId
              and artifact.execution.project.workspace.id = :workspaceId
            """)
    Optional<ExecutionArtifact> findScoped(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("executionId") UUID executionId,
            @Param("artifactId") UUID artifactId);
}
