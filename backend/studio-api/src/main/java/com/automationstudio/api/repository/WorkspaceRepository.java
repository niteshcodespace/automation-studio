package com.automationstudio.api.repository;

import com.automationstudio.api.entity.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsByName(String name);
}
