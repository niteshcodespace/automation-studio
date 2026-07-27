package com.automationstudio.api.service;

import com.automationstudio.api.service.command.ScheduleExecutionCommand;
import com.automationstudio.api.service.result.SchedulingResult;

public interface RunnerSchedulingService {

    SchedulingResult scheduleNext(ScheduleExecutionCommand command);
}
