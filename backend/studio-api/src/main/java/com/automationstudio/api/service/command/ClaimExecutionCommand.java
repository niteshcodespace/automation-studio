package com.automationstudio.api.service.command;

import java.time.Duration;

public record ClaimExecutionCommand(String runnerId, Duration leaseDuration) {
}
