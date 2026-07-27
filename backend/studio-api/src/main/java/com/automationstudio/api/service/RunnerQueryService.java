package com.automationstudio.api.service;

import com.automationstudio.api.service.query.RunnerQueryFilter;
import com.automationstudio.api.service.result.RunnerDetailsResult;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface RunnerQueryService {

    RunnerDetailsResult get(UUID runnerId);

    Page<RunnerDetailsResult> list(RunnerQueryFilter filter, Pageable pageable);

    Page<RunnerDetailsResult> list(
            RunnerQueryFilter filter, Pageable pageable, String direction);

    Page<RunnerDetailsResult> list(
            RunnerQueryFilter filter,
            Pageable pageable,
            String direction,
            Integer requestedPage,
            Integer requestedSize);
}
