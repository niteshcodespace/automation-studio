package com.automationstudio.api.repository;

import com.automationstudio.api.entity.Execution;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
}
