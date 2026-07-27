package com.automationstudio.api.service.command;

import java.time.Duration;

public record ScheduleExecutionCommand(String runnerKey, Duration leaseDuration) {
}
