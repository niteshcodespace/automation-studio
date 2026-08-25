package com.automationstudio.engine.selenium;

import java.time.Duration;

record SeleniumContainmentLimits(Duration commandTimeout, Duration handshakeTimeout,
        Duration cleanupTimeout, long maxOutputBytes, long memoryBytes, double cpus, int pids,
        long tmpfsBytes) {
    static SeleniumContainmentLimits defaults() {
        return new SeleniumContainmentLimits(Duration.ofSeconds(10), Duration.ofSeconds(5),
                Duration.ofMillis(1500), 65_536, 256L * 1024 * 1024, 1.0, 64, 32L * 1024 * 1024);
    }
    SeleniumContainmentLimits {
        if (commandTimeout == null || commandTimeout.isNegative() || commandTimeout.isZero()
                || handshakeTimeout == null || handshakeTimeout.isNegative() || handshakeTimeout.isZero()
                || cleanupTimeout == null || cleanupTimeout.isNegative() || cleanupTimeout.isZero()
                || cleanupTimeout.compareTo(Duration.ofSeconds(2)) >= 0 || maxOutputBytes < 1024
                || memoryBytes < 64L * 1024 * 1024 || cpus <= 0 || pids < 2 || tmpfsBytes < 1024) {
            throw new IllegalArgumentException("Invalid Selenium containment limits");
        }
    }
}
