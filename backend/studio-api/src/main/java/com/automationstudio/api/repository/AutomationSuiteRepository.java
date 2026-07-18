package com.automationstudio.api.repository;

import com.automationstudio.api.entity.AutomationSuite;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutomationSuiteRepository extends JpaRepository<AutomationSuite, UUID> {
}
