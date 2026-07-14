package com.automationstudio.api.repository;

import com.automationstudio.api.entity.Environment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {
}
