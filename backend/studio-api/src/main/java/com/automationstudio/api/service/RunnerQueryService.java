package com.automationstudio.api.service;

import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.service.result.RunnerDetailsResult;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface RunnerQueryService {

    RunnerDetailsResult get(UUID runnerId);

    Page<RunnerDetailsResult> list(RunnerStatus status, Pageable pageable);
}
