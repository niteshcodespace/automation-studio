package com.automationstudio.api.service.command;

import java.time.Duration;

public record ReclaimExecutionLeaseCommand(String newRunnerId, Duration leaseDuration) {
}
