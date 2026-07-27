package com.automationstudio.api.domain;

import java.util.List;
import java.util.Map;

public class RunnerCompatibilityEvaluator {

    public CompatibilityResult evaluate(
            SchedulingRequirements requirements, RunnerCapabilities runner) {
        if (!runner.supportsEngine(requirements.engineId())) {
            return CompatibilityResult.ENGINE_MISMATCH;
        }
        if (!contains(runner.capabilities(), requirements.requiredCapabilities())) {
            return CompatibilityResult.CAPABILITY_MISMATCH;
        }
        if (!runner.labels().entrySet().containsAll(
                requirements.requiredLabels().entrySet())) {
            return CompatibilityResult.LABEL_MISMATCH;
        }
        return CompatibilityResult.COMPATIBLE;
    }

    private boolean contains(Object actual, Object required) {
        if (required instanceof Map<?, ?> requiredMap) {
            if (!(actual instanceof Map<?, ?> actualMap)) {
                return false;
            }
            return requiredMap.entrySet().stream().allMatch(entry ->
                    actualMap.containsKey(entry.getKey())
                            && contains(actualMap.get(entry.getKey()), entry.getValue()));
        }
        if (required instanceof List<?> requiredList) {
            if (!(actual instanceof List<?> actualList)) {
                return false;
            }
            return requiredList.stream().allMatch(requiredItem ->
                    actualList.stream().anyMatch(actualItem ->
                            contains(actualItem, requiredItem)));
        }
        return required == null ? actual == null : required.equals(actual);
    }
}
