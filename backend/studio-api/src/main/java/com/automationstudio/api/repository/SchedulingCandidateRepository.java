package com.automationstudio.api.repository;

import com.automationstudio.api.domain.RunnerCapabilities;
import com.automationstudio.api.domain.SchedulingCandidate;
import java.util.Optional;

public interface SchedulingCandidateRepository {

    Optional<SchedulingCandidate> findNextCompatible(RunnerCapabilities runner);
}
