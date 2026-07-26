package com.automationstudio.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "automation-studio.runners.health")
public record RunnerHealthProperties(
        @DefaultValue("PT1M") Duration onlineThreshold,
        @DefaultValue("PT5M") Duration offlineThreshold) {

    public RunnerHealthProperties {
        if (onlineThreshold == null
                || onlineThreshold.isZero()
                || onlineThreshold.isNegative()) {
            throw new IllegalArgumentException(
                    "Runner online health threshold must be positive");
        }
        if (offlineThreshold == null
                || offlineThreshold.isZero()
                || offlineThreshold.isNegative()) {
            throw new IllegalArgumentException(
                    "Runner offline health threshold must be positive");
        }
        if (offlineThreshold.compareTo(onlineThreshold) <= 0) {
            throw new IllegalArgumentException(
                    "Runner offline health threshold must be greater than online threshold");
        }
    }
}
