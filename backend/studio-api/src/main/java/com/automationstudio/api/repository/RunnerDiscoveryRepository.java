package com.automationstudio.api.repository;

import com.automationstudio.api.service.query.RunnerQueryFilter;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface RunnerDiscoveryRepository {

    Page<UUID> findRunnerIds(
            RunnerQueryFilter filter,
            OffsetDateTime evaluatedAt,
            Duration onlineThreshold,
            Duration offlineThreshold,
            Pageable pageable);
}
