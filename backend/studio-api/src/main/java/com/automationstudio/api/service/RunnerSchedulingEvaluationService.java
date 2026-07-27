package com.automationstudio.api.service;

import com.automationstudio.api.domain.RunnerSchedulingEligibility;

public interface RunnerSchedulingEvaluationService {

    RunnerSchedulingEligibility evaluate(String runnerKey);
}
