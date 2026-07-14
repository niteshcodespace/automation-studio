package com.automationstudio.api.repository;

import com.automationstudio.api.entity.TestSuite;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestSuiteRepository extends JpaRepository<TestSuite, UUID> {
}
