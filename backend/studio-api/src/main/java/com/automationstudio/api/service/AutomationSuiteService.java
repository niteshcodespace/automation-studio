package com.automationstudio.api.service;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.entity.AutomationSuite;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AutomationSuiteService {

    AutomationSuite create(UUID projectId, AutomationSuite suite);

    AutomationSuite get(UUID projectId, UUID suiteId);

    Page<AutomationSuite> list(
            UUID projectId, AutomationSuiteStatus status, Pageable pageable);

    AutomationSuite update(UUID projectId, UUID suiteId, AutomationSuite updates);

    AutomationSuite changeStatus(
            UUID projectId, UUID suiteId, AutomationSuiteStatus status);

    void delete(UUID projectId, UUID suiteId);
}
