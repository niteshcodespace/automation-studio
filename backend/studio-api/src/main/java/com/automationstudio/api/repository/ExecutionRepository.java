package com.automationstudio.api.repository;

import com.automationstudio.api.entity.Execution;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {

    @Override
    @EntityGraph(attributePaths = {"project", "environment", "testSuite"})
    Page<Execution> findAll(Pageable pageable);
}
