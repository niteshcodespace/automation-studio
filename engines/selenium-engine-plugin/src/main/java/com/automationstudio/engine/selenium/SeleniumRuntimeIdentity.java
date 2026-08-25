package com.automationstudio.engine.selenium;

import java.util.UUID;

record SeleniumRuntimeIdentity(UUID executionId, String workerName, String executionLabel,
        String containerId, Stage stage) {
    enum Stage { CREATED, STARTED, READY }
    SeleniumRuntimeIdentity {
        String suffix = executionId == null ? "" : executionId.toString().replace("-", "");
        if (executionId == null || !workerName.equals("as-selenium-worker-" + suffix)
                || !executionLabel.equals(suffix) || containerId == null
                || !containerId.matches("[a-f0-9]{64}") || stage == null) {
            throw new IllegalArgumentException("Invalid Selenium runtime identity");
        }
    }
    SeleniumRuntimeIdentity at(Stage next) { return new SeleniumRuntimeIdentity(
            executionId, workerName, executionLabel, containerId, next); }
}
