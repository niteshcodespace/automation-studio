package com.automationstudio.api.repository;

import com.automationstudio.api.entity.ExecutionTestCase;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionTestCaseRepository extends JpaRepository<ExecutionTestCase, UUID> {

    List<ExecutionTestCase> findByExecutionIdOrderBySequenceNumberAsc(UUID executionId);

    boolean existsByExecutionId(UUID executionId);

    long countByExecutionId(UUID executionId);
}
