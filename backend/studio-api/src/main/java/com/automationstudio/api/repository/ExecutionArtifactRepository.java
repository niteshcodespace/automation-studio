package com.automationstudio.api.repository;

import com.automationstudio.api.entity.ExecutionArtifact;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionArtifactRepository extends JpaRepository<ExecutionArtifact, UUID> {
}
